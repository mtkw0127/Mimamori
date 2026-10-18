package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `RecorderScreen`（ローカルプレビュー）と `WebRtcManualP2PSession`（相手に送る Offerer 側）の
 * 両方から、同じカメラ映像を参照するために用意した。
 *
 * CameraX（`ImageAnalysis`）でカメラのフレームを受け取り、YUV_420_888 を WebRTC の
 * [org.webrtc.VideoFrame] に変換して手動で流し込む。WebRTC 標準の `Camera2Capturer` は使わない
 * ため、ズームやレンズ選択など CameraX の機能をそのまま活かせる。
 *
 * ⚠️ `@Singleton` はこのクラスの「インスタンス」を1つにするだけで、
 * 中のカメラが自動で起動しっぱなしになるわけではない。
 * カメラの起動・停止は [start] / [stop] を呼んだときだけ行うこと
 * （docs/architecture.md §7.1「視聴者がいないときは何もしない」）。
 */
@Singleton
class LocalCameraSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: PeerConnectionFactory,
) {
    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    val videoTrack: StateFlow<VideoTrack?> = _videoTrack.asStateFlow()

    /** 今使っているカメラがフロント（インカメラ）かどうか。カメラが動いていないときは null */
    private val _isFrontFacingCamera = MutableStateFlow<Boolean?>(null)
    val isFrontFacingCamera: StateFlow<Boolean?> = _isFrontFacingCamera.asStateFlow()

    /** 今使っているカメラの [CameraOption.deviceName]。カメラが動いていないときは null */
    private val _currentCameraDeviceName = MutableStateFlow<String?>(null)
    val currentCameraDeviceName: StateFlow<String?> = _currentCameraDeviceName.asStateFlow()

    /** 選べるレンズ。撮影を開始するまでは空リスト */
    private val _availableCameras = MutableStateFlow<List<CameraOption>>(emptyList())
    val availableCameras: StateFlow<List<CameraOption>> = _availableCameras.asStateFlow()

    /** 今のズーム倍率と、そのレンズが取れる範囲。カメラが動いていないときは null */
    private val _zoomInfo = MutableStateFlow<ZoomInfo?>(null)
    val zoomInfo: StateFlow<ZoomInfo?> = _zoomInfo.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: CaptureLifecycleOwner? = null
    private var analysisExecutor: ExecutorService? = null
    private var videoSource: VideoSource? = null
    private var boundCamera: Camera? = null
    private var zoomStateObserver: Observer<ZoomState>? = null

    /**
     * 今の端末の向き（[android.view.Surface.ROTATION_0] など）。[updateDeviceRotation] で随時更新する。
     *
     * ⚠️ これを [ImageAnalysis.setTargetRotation] 経由で CameraX 側に教える、というのが素直な
     * やり方に見えるが、bind 済みのカメラに一切触れず向きの補正だけ更新したいので、CameraX には
     * 渡さない。センサーの向き（[sensorOrientation]、bind 時に一度取得するだけで固定）とこの値から、
     * 送る [VideoFrame] の回転量を自分で計算する（[rotationDegreesFor]）。
     */
    private var deviceRotation: Int = android.view.Surface.ROTATION_0

    /** 今のレンズのセンサー向き（度）。`bind` のたびに取得し直す。回転時には変わらない */
    private var sensorOrientation: Int = 0

    data class CameraOption(
        val deviceName: String,
        val isFrontFacing: Boolean,
        /** 「背面(超広角)」「前面」のような画面表示用のラベル */
        val label: String,
    )

    data class ZoomInfo(val ratio: Float, val minRatio: Float, val maxRatio: Float)

    /** WebRTC に送るのと同じフレームを、横から受け取りたいもの（顔検出など）用 */
    fun interface FrameObserver {
        /**
         * カメラの解析スレッドから毎フレーム同期的に呼ばれる。[buffer] は呼び出しが終わると解放されるので、
         * 後で使うならこの中でコピーすること。重い処理をここでするとカメラ映像がカクつく
         */
        fun onFrame(buffer: VideoFrame.I420Buffer, rotationDegrees: Int)
    }

    @Volatile
    var frameObserver: FrameObserver? = null

    sealed class LocalCameraSessionStartException(message: String) : Exception(message) {
        class CameraNotFoundException :
            LocalCameraSessionStartException("There is not available camera.")
    }

    suspend fun start() {
        val provider = cameraProvider ?: awaitCameraProvider().also { cameraProvider = it }
        val options = listCameraOptions(provider)
        _availableCameras.value = options

        // 背面カメラを優先する
        val preferred = options.firstOrNull { !it.isFrontFacing }
            ?: options.firstOrNull()
            ?: throw LocalCameraSessionStartException.CameraNotFoundException()
        bind(provider, preferred.deviceName)
    }

    /**
     * 指定したレンズに切り替える（段階ズーム）。
     * 撮影中でなければ何もしない。
     */
    fun switchTo(deviceName: String) {
        val provider = cameraProvider ?: return
        if (deviceName == _currentCameraDeviceName.value) return
        bind(provider, deviceName)
    }

    /** ズーム倍率を変更する。[ZoomInfo.minRatio]〜[ZoomInfo.maxRatio] の範囲に丸める */
    fun setZoomRatio(ratio: Float) {
        val camera = boundCamera ?: return
        val zoom = _zoomInfo.value ?: return
        camera.cameraControl.setZoomRatio(ratio.coerceIn(zoom.minRatio, zoom.maxRatio))
    }

    /**
     * 端末の回転（[android.view.Surface.ROTATION_0] など）が変わるたびに呼ぶ。
     * カメラには一切触れない（上の [deviceRotation] のコメント参照）ので、
     * 送信中の [VideoTrack] を切らずに、次に送るフレームから向きの補正だけ更新できる。
     */
    fun updateDeviceRotation(rotation: Int) {
        deviceRotation = rotation
    }

    fun stop() {
        detachZoomObserver()
        boundCamera = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        videoSource?.dispose()
        videoSource = null

        _videoTrack.value = null
        _isFrontFacingCamera.value = null
        _currentCameraDeviceName.value = null
        _zoomInfo.value = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(provider: ProcessCameraProvider, deviceName: String) {
        // 前のレンズを解放してから、新しいレンズに繋ぎ直す
        // （CameraX は LifecycleOwner が DESTROYED になった use case を自動で unbind してくれる）
        detachZoomObserver()
        lifecycleOwner?.destroy()
        analysisExecutor?.shutdown()

        val owner = CaptureLifecycleOwner().also { lifecycleOwner = it }
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val source = videoSource ?: factory.createVideoSource(false).also { videoSource = it }
        val capturerObserver = source.capturerObserver

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // 固定値を明示的に渡しておく。何も渡さないと、CameraX が内部でディスプレイの回転を
            // 監視して targetRotation を自動更新してしまう（＝カメラに無用な再設定がかかる余地を
            // 残す）。向きの補正は常にこちら側（[rotationDegreesFor]）で計算するので、ここは固定でよい。
            .setTargetRotation(android.view.Surface.ROTATION_0)
            .build()
        analysis.setAnalyzer(executor) { image -> analyzeFrame(image, capturerObserver) }

        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == deviceName } }
            .build()

        owner.start()
        val camera = provider.bindToLifecycle(owner, selector, analysis)
        boundCamera = camera
        attachZoomObserver(camera)
        val isFront = isFrontFacing(camera)
        sensorOrientation = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        capturerObserver.onCapturerStarted(true)
        _isFrontFacingCamera.value = isFront
        _currentCameraDeviceName.value = deviceName
        _videoTrack.value = _videoTrack.value ?: factory.createVideoTrack("VIDEO_TRACK_ID", source)
    }

    /**
     * レンズを切り替えるたびに、そのレンズのズーム範囲を [zoomInfo] に反映させる。
     *
     * あわせて、最初はそのレンズで一番広く写る倍率（最小倍率）から始める。CameraX の初期値は 1.0 倍だが、
     * 超広角を論理カメラにまとめている端末では最小倍率が 0.5 倍などになっていて、部屋全体を
     * 見渡したい見守り用途では、最初から一番広い画角の方が使いやすい。
     * zoomState は bind 直後だとまだ値が入っていないことがあるので、最初に届いた値で設定する。
     */
    private fun attachZoomObserver(camera: Camera) {
        var initialZoomApplied = false
        val observer = Observer<ZoomState> { state ->
            if (!initialZoomApplied) {
                initialZoomApplied = true
                camera.cameraControl.setZoomRatio(state.minZoomRatio)
            }
            _zoomInfo.value = ZoomInfo(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
        }
        zoomStateObserver = observer
        camera.cameraInfo.zoomState.observeForever(observer)
    }

    private fun detachZoomObserver() {
        val camera = boundCamera
        val observer = zoomStateObserver
        if (camera != null && observer != null) {
            camera.cameraInfo.zoomState.removeObserver(observer)
        }
        zoomStateObserver = null
    }

    private fun analyzeFrame(image: ImageProxy, capturerObserver: CapturerObserver) {
        try {
            val rotationDegrees = rotationDegreesFor(sensorOrientation, deviceRotation, _isFrontFacingCamera.value == true)
            val buffer = image.toI420Buffer()
            val frame = VideoFrame(
                buffer,
                rotationDegrees,
                image.imageInfo.timestamp
            )
            capturerObserver.onFrameCaptured(frame)
            // 顔検出などで失敗しても、映像の送信やフレームの解放は止めない
            runCatching { frameObserver?.onFrame(buffer, rotationDegrees) }
                .onFailure { Timber.w(it, "frame observer failed") }
            frame.release()
        } catch (e: Exception) {
            Timber.w(e, "failed to convert a camera frame")
        } finally {
            image.close()
        }
    }

    /**
     * センサーの向きと今の端末の向きから、映像を正立させるのに必要な回転量を計算する
     * （Camera2 の定番の式。CameraX が内部で [ImageAnalysis.targetRotation] から計算している
     * のと同じもので、ここでは CameraX に触れず自前で計算する）。
     */
    private fun rotationDegreesFor(sensorOrientation: Int, deviceRotation: Int, isFrontFacing: Boolean): Int {
        val deviceDegrees = when (deviceRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (isFrontFacing) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resume(future.get()) },
                { runnable -> runnable.run() }, // 呼び出し元のスレッドで十分軽い処理
            )
        }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun isFrontFacing(camera: Camera): Boolean =
        Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

    @OptIn(ExperimentalCamera2Interop::class)
    private fun listCameraOptions(provider: ProcessCameraProvider): List<CameraOption> {
        fun facing(info: CameraInfo) =
            Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)

        fun deviceId(info: CameraInfo) = Camera2CameraInfo.from(info).cameraId

        fun focalLength(info: CameraInfo): Float =
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: Float.MAX_VALUE

        val infos = provider.availableCameraInfos
        val backDeviceNames = infos.filter { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy(::focalLength)
            .map(::deviceId)
        val frontDeviceNames =
            infos.filter { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
                .map(::deviceId)

        return labelBackCameras(backDeviceNames) +
                frontDeviceNames.map { CameraOption(it, isFrontFacing = true, label = "前面") }
    }

    // 背面カメラの数に応じて「超広角/標準/望遠」のラベルを振る（焦点距離の短い順）
    private fun labelBackCameras(sortedDeviceNames: List<String>): List<CameraOption> {
        val labels = when (sortedDeviceNames.size) {
            0 -> emptyList()
            1 -> listOf(null)
            2 -> listOf("超広角", "標準")
            else -> listOf("超広角") +
                    List(sortedDeviceNames.size - 2) { "標準${it + 1}" } +
                    listOf("望遠")
        }
        return sortedDeviceNames.mapIndexed { index, deviceName ->
            val lens = labels.getOrNull(index)
            CameraOption(
                deviceName = deviceName,
                isFrontFacing = false,
                label = if (lens == null) "背面" else "背面($lens)",
            )
        }
    }

    /** CameraX を Activity/Fragment の外（この Singleton）から使うための手動 LifecycleOwner */
    private class CaptureLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

/** YUV_420_888 の [ImageProxy] を、WebRTC が扱える [JavaI420Buffer] に変換する */
private fun ImageProxy.toI420Buffer(): JavaI420Buffer {
    val buffer = JavaI420Buffer.allocate(width, height)
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2

    copyPlane(
        planes[0].buffer,
        planes[0].rowStride,
        planes[0].pixelStride,
        buffer.dataY,
        buffer.strideY,
        width,
        height
    )
    copyPlane(
        planes[1].buffer,
        planes[1].rowStride,
        planes[1].pixelStride,
        buffer.dataU,
        buffer.strideU,
        chromaWidth,
        chromaHeight
    )
    copyPlane(
        planes[2].buffer,
        planes[2].rowStride,
        planes[2].pixelStride,
        buffer.dataV,
        buffer.strideV,
        chromaWidth,
        chromaHeight
    )

    return buffer
}

/**
 * カメラの1プレーン分を、行ごとに詰めてコピーする。
 * Android の Image のプレーンは `rowStride`（1行のバイト数、末尾に余白があることがある）と
 * `pixelStride`（1ピクセルのバイト数、chroma が半平面 [NV21/NV12] のときは 2 になる）を持つため、
 * そのまま `ByteBuffer` 同士を copy できない。
 */
private fun copyPlane(
    src: ByteBuffer,
    srcRowStride: Int,
    srcPixelStride: Int,
    dst: ByteBuffer,
    dstRowStride: Int,
    width: Int,
    height: Int,
) {
    val srcDup = src.duplicate()
    val row = ByteArray(width)
    for (y in 0 until height) {
        if (srcPixelStride == 1) {
            srcDup.position(y * srcRowStride)
            srcDup.get(row, 0, width)
        } else {
            val rowStart = y * srcRowStride
            for (x in 0 until width) {
                row[x] = srcDup.get(rowStart + x * srcPixelStride)
            }
        }
        dst.position(y * dstRowStride)
        dst.put(row, 0, width)
    }
}
