package io.github.mtkw0127.mimamori.monitoring

import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.VideoFrame
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recorder のカメラ映像から顔を探し、「顔が一定時間見えていない」状態（[FaceWatchState.alerting]）を判定する。
 * 赤ちゃんのベッドを見守る想定で、顔に何かがかかった／ベッドからいなくなったときに Viewer へ知らせるのに使う。
 *
 * ⚠️ カメラからは「顔が隠れた」と「いなくなった」の区別がつかないので、どちらも「顔が見えない」として扱う。
 * また ML Kit は主に大人の顔で学習されているため、新生児・うつ伏せ・横向き・暗い部屋では、
 * 何も起きていなくても顔を見失うことがある。あくまで見守りの補助で、安全を保証するものではない。
 *
 * 顔検出は重いので、全フレームではなく [DETECTION_INTERVAL_MILLIS] ごとに1回だけ行う。
 * 判定（[onDetectionResult]）はメインスレッドで行うので、状態を表すフィールドはロック不要。
 */
@Singleton
class FaceWatcher @Inject constructor() : LocalCameraSession.FrameObserver {

    data class FaceWatchState(
        /** 直近の検出で顔が見つかったか */
        val faceVisible: Boolean,
        /** 顔が [ALERT_AFTER_MILLIS] 以上見えていない。Viewer に通知すべき状態 */
        val alerting: Boolean,
    )

    /** 見守り中の状態。カメラが動いていない（[start] 前・[stop] 後）ときは null */
    private val _state = MutableStateFlow<FaceWatchState?>(null)
    val state: StateFlow<FaceWatchState?> = _state.asStateFlow()

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // ベッドを少し離れたところから写すので、画面に対して小さめの顔も拾う
            .setMinFaceSize(0.08f)
            .build(),
    )

    // ここから2つはカメラの解析スレッドで使う（detecting だけは完了時にメインスレッドからも書く）
    @Volatile
    private var detecting = false
    private var lastDetectionStartedAt = 0L

    // ここから下はメインスレッドからだけ触る
    /** [stop] → [start] をまたいで、前回の見守りの検出結果が今回に紛れ込まないよう区別する番号 */
    @Volatile
    private var generation = 0
    private var lastFaceSeenAt = 0L
    private var consecutiveFaceCount = 0

    /** カメラを起動したときに呼ぶ。最初から顔が見えなければ、[ALERT_AFTER_MILLIS] 後に通知状態になる */
    fun start() {
        generation++
        lastFaceSeenAt = SystemClock.elapsedRealtime()
        consecutiveFaceCount = 0
        _state.value = FaceWatchState(faceVisible = false, alerting = false)
    }

    fun stop() {
        generation++
        _state.value = null
    }

    /** カメラの解析スレッドから、毎フレーム同期的に呼ばれる。ここで [buffer] を保持してはいけない */
    override fun onFrame(buffer: VideoFrame.I420Buffer, rotationDegrees: Int) {
        val now = SystemClock.elapsedRealtime()
        if (detecting || now - lastDetectionStartedAt < DETECTION_INTERVAL_MILLIS) return
        lastDetectionStartedAt = now
        detecting = true

        val gen = generation
        val image = InputImage.fromByteArray(
            buffer.toGrayNv21(),
            buffer.width,
            buffer.height,
            rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21,
        )
        // リスナーは何も指定しなければメインスレッドで呼ばれる
        detector.process(image)
            .addOnSuccessListener { faces -> onDetectionResult(gen, faceFound = faces.isNotEmpty()) }
            .addOnFailureListener { e ->
                // 検出に失敗し続けたときに黙って「異常なし」にならないよう、見つからなかった扱いにする
                Timber.w(e, "face detection failed")
                onDetectionResult(gen, faceFound = false)
            }
            .addOnCompleteListener { detecting = false }
    }

    private fun onDetectionResult(gen: Int, faceFound: Boolean) {
        if (gen != generation) return
        val current = _state.value ?: return
        val now = SystemClock.elapsedRealtime()

        val alerting = if (faceFound) {
            lastFaceSeenAt = now
            consecutiveFaceCount++
            // 一瞬映っただけで解除すると通知が点いたり消えたりするので、続けて見えてから解除する
            current.alerting && consecutiveFaceCount < RECOVER_AFTER_CONSECUTIVE_FACES
        } else {
            consecutiveFaceCount = 0
            current.alerting || now - lastFaceSeenAt >= ALERT_AFTER_MILLIS
        }
        _state.value = FaceWatchState(faceVisible = faceFound, alerting = alerting)
    }

    private companion object {
        const val DETECTION_INTERVAL_MILLIS = 1_000L

        /**
         * 顔が見えなくなってから通知するまでの時間。短いほど早く気づけるが、
         * 寝返りや手で顔をこすっただけでも通知されやすくなる
         */
        const val ALERT_AFTER_MILLIS = 5_000L

        /** 通知中に、何回続けて顔が見つかったら解除するか（[DETECTION_INTERVAL_MILLIS] ごとに1回） */
        const val RECOVER_AFTER_CONSECUTIVE_FACES = 3
    }
}

/**
 * 輝度（Y）だけをコピーした NV21 の配列を作る。顔検出には明るさの情報だけで十分なので、
 * 色（VU）は無彩色（128）で埋めてコピーを省く。
 */
private fun VideoFrame.I420Buffer.toGrayNv21(): ByteArray {
    val ySize = width * height
    val chromaSize = 2 * ((width + 1) / 2) * ((height + 1) / 2)
    val nv21 = ByteArray(ySize + chromaSize)
    val src = dataY.duplicate()
    for (y in 0 until height) {
        src.position(y * strideY)
        src.get(nv21, y * width, width)
    }
    nv21.fill(128.toByte(), ySize, nv21.size)
    return nv21
}
