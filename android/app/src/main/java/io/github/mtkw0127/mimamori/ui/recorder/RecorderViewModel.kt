package io.github.mtkw0127.mimamori.ui.recorder

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.signaling.RecorderNsdAdvertiser
import io.github.mtkw0127.mimamori.signaling.SignalingConnection
import io.github.mtkw0127.mimamori.signaling.SignalingMessage
import io.github.mtkw0127.mimamori.signaling.asSignalingConnection
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import io.github.mtkw0127.mimamori.webrtc.ManualP2PSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import timber.log.Timber
import java.net.ServerSocket
import javax.inject.Inject

/** Recorder が Viewer との接続までにたどる段階。画面に出す文言の出し分けに使う */
enum class SignalingStatus {
    /** mDNS で告知中。まだ誰も接続してきていない */
    WAITING_FOR_VIEWER,

    /** Viewer が TCP で繋いできた。offer/answer のやり取り中 */
    NEGOTIATING,

    /** WebRTC の接続まで完了した */
    CONNECTED,

    /** Viewer との接続が切れた（次の Viewer を待つ） */
    DISCONNECTED,

    FAILED,
}

/**
 * [LocalCameraSession]（アプリに1つだけのカメラ）を Recorder 画面に繋ぐ ViewModel。
 * あわせて、mDNS での告知と TCP でのシグナリングを行い、Viewer からの接続を自動で受け付ける（M3）。
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val cameraSession: LocalCameraSession,
    val eglBase: EglBase,
    private val session: ManualP2PSession,
    private val advertiser: RecorderNsdAdvertiser,
) : ViewModel() {

    val videoTrack: StateFlow<VideoTrack?> = cameraSession.videoTrack
    val isFrontFacingCamera: StateFlow<Boolean?> = cameraSession.isFrontFacingCamera
    val currentCameraDeviceName: StateFlow<String?> = cameraSession.currentCameraDeviceName

    /** 端末が持つレンズの一覧。撮影を開始するまでは空リスト */
    val availableCameras: StateFlow<List<LocalCameraSession.CameraOption>> = cameraSession.availableCameras
    val zoomInfo: StateFlow<LocalCameraSession.ZoomInfo?> = cameraSession.zoomInfo

    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = session.connectionState

    private val _signalingStatus = MutableStateFlow(SignalingStatus.WAITING_FOR_VIEWER)
    val signalingStatus: StateFlow<SignalingStatus> = _signalingStatus.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var connection: SignalingConnection? = null
    private var signalingStarted = false

    suspend fun start() = cameraSession.start()

    fun stop() = cameraSession.stop()

    fun switchCamera(deviceName: String) = cameraSession.switchTo(deviceName)

    fun setZoomRatio(ratio: Float) = cameraSession.setZoomRatio(ratio)

    /**
     * mDNS での告知と、Viewer からの TCP 接続の待ち受けを始める。
     * 複数回呼ばれても（画面の再コンポーズなどで）1回しか始めないようにしている。
     *
     * v1 は同時視聴 1 台の想定なので、1人の Viewer と繋がったらそれで完了とする
     * （切断後にまた別の Viewer を自動で待ち直す、ということはしない。再度待ち受けたい
     * 場合は Recorder 画面を開き直して、新しい [RecorderViewModel] インスタンスにする）。
     */
    fun startSignaling() {
        if (signalingStarted) return
        signalingStarted = true

        viewModelScope.launch {
            try {
                _signalingStatus.value = SignalingStatus.WAITING_FOR_VIEWER

                val socket = withContext(Dispatchers.IO) {
                    val server = ServerSocket(0).also { serverSocket = it }
                    advertiser.advertise(deviceDisplayName(), server.localPort)
                    server.accept() // Viewer が繋いでくるまでブロックする
                }
                // 1人見つかったら、他の Viewer に見つからないよう告知をやめる
                advertiser.stop()

                _signalingStatus.value = SignalingStatus.NEGOTIATING
                val conn = socket.asSignalingConnection().also { connection = it }

                // Offerer（映像を送る側）なので、自分から offer を送って交渉を始める
                val offerSdp = session.createOffer()
                conn.send(SignalingMessage.Offer(offerSdp))

                conn.incoming().collect { message ->
                    when (message) {
                        is SignalingMessage.Answer -> {
                            session.acceptAnswer(message.sdp)
                            _signalingStatus.value = SignalingStatus.CONNECTED
                        }

                        is SignalingMessage.Disconnect -> conn.close()
                        is SignalingMessage.Offer -> Unit // Recorder 側には来ないはず
                    }
                }
                // ここに来るのは、TCP 接続が切れて incoming() の Flow が終わったとき
                _signalingStatus.value = SignalingStatus.DISCONNECTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "signaling failed")
                _signalingStatus.value = SignalingStatus.FAILED
            }
        }
    }

    private fun deviceDisplayName(): String = "Mimamori-${Build.MODEL}"

    override fun onCleared() {
        advertiser.stop()
        connection?.close()
        serverSocket?.let { runCatching { it.close() } }
        session.close()
    }
}
