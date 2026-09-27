package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera2Enumerator
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `RecorderScreen`（ローカルプレビュー）と `WebRtcManualP2PSession`（相手に送る Offerer 側）の
 * 両方から、同じカメラ映像を参照するために用意した。
 *
 * ⚠️ `@Singleton` はこのクラスの「インスタンス」を1つにするだけで、
 * 中のカメラが自動で起動しっぱなしになるわけではない。
 * カメラの起動・停止は [start] / [stop] を呼んだときだけ行うこと
 * （docs/architecture.md §7.1「視聴者がいないときは何もしない」）。
 *
 * 進め方のヒント:
 *  1. `RecorderScreen.kt` の `PreviewPlaceholder` に書いた
 *     Camera2Enumerator → VideoCapturer → VideoSource → VideoTrack の生成処理を
 *     ここに移す（`factory` と `eglBase` は Hilt がコンストラクタで渡してくれる）
 *  2. [start] は「まだ起動していなければ起動する」（同じカメラを二重に startCapture しない）
 *  3. [stop] は「作った物を全部、逆順に解放する」（RecorderScreen で書いた後片付けと同じ）
 *  4. [videoTrack] を `StateFlow<VideoTrack?>` として公開し、
 *     start() で作られたら値をセット、stop() で null に戻す
 *  5. `RecorderScreen.kt` は、自前でカメラを作るのをやめて、この [LocalCameraSession] を
 *     Hilt から受け取って使う形に書き換える（画面に出入りするタイミングで start/stop を呼ぶ）
 */
@Singleton
class LocalCameraSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
) {
    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    val videoTrack: StateFlow<VideoTrack?> = _videoTrack.asStateFlow()

    /** 今使っているカメラがフロント（インカメラ）かどうか。カメラが動いていないときは null */
    private val _isFrontFacingCamera = MutableStateFlow<Boolean?>(null)
    val isFrontFacingCamera: StateFlow<Boolean?> = _isFrontFacingCamera.asStateFlow()

    private val _videoCapture = MutableStateFlow<VideoCapturer?>(null)
    private val _videoSource = MutableStateFlow<VideoSource?>(null)
    private val _surfaceTextHelper = MutableStateFlow<SurfaceTextureHelper?>(null)

    sealed class LocalCameraSessionStartException(message: String) : Exception(message) {
        class CameraNotFoundException :
            LocalCameraSessionStartException("There is not available camera.")
    }

    fun start() {
        val (capturer, isFrontFacing) = createCameraCapturer(context)
            ?: throw LocalCameraSessionStartException.CameraNotFoundException()
        _videoCapture.value = capturer
        _isFrontFacingCamera.value = isFrontFacing
        _videoSource.value = factory.createVideoSource(capturer.isScreencast)
        _surfaceTextHelper.value = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBase.eglBaseContext
        )
        _videoTrack.value = factory.createVideoTrack("VIDEO_TRACK_ID", _videoSource.value)
        capturer.initialize(
            _surfaceTextHelper.value,
            context,
            checkNotNull(_videoSource.value).capturerObserver // これにより Source と Capturer が結びつきます
        )
        capturer.startCapture(1280, 720, 15) // 解像度とFPSを指定
    }

    fun stop() {
        try {
            _videoCapture.value?.stopCapture()
        } catch (e: InterruptedException) {
            Timber.w(e)
        }
        _videoCapture.value?.dispose()
        _surfaceTextHelper.value?.dispose()
        _videoSource.value?.dispose()

        _videoCapture.value = null
        _surfaceTextHelper.value = null
        _videoSource.value = null
        _videoTrack.value = null
        _isFrontFacingCamera.value = null
    }

    /** どのカメラを掴んだかを、映像そのものと合わせて返す */
    private data class CapturerWithFacing(val capturer: VideoCapturer, val isFrontFacing: Boolean)

    // アウトカメラを優先して探す
    private fun createCameraCapturer(context: Context): CapturerWithFacing? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return CapturerWithFacing(videoCapturer, isFrontFacing = false)
                }
            }
        }

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                // インカメラ用のキャプチャを作成して返す
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return CapturerWithFacing(videoCapturer, isFrontFacing = true)
                }
            }
        }

        // カメラが見つからない場合
        return null
    }
}
