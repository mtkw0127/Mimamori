package io.github.mtkw0127.mimamori.ui.recorder

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.pairing.DeviceIdentity
import io.github.mtkw0127.mimamori.pairing.OtpSession
import io.github.mtkw0127.mimamori.pairing.PairedViewer
import io.github.mtkw0127.mimamori.pairing.PairedViewerStore
import io.github.mtkw0127.mimamori.pairing.constantTimeEquals
import io.github.mtkw0127.mimamori.pairing.hmacSha256
import io.github.mtkw0127.mimamori.signaling.RecorderNsdAdvertiser
import io.github.mtkw0127.mimamori.signaling.SignalingConnection
import io.github.mtkw0127.mimamori.signaling.SignalingMessage
import io.github.mtkw0127.mimamori.signaling.asSignalingConnection
import io.github.mtkw0127.mimamori.webrtc.LocalCameraSession
import io.github.mtkw0127.mimamori.webrtc.ManualP2PSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.net.Socket
import javax.inject.Inject

/** Recorder が Viewer との接続までにたどる段階。画面に出す文言の出し分けに使う */
enum class SignalingStatus {
    /** mDNS で告知中。まだ誰も接続してきていない */
    WAITING_FOR_VIEWER,

    /** Viewer が TCP で繋いできた。ペアリングや offer/answer のやり取り中 */
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
 * ペアリング済みでない Viewer は、OTP（[startPairing]）を知らない限り接続できない（M4）。
 */
@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val cameraSession: LocalCameraSession,
    val eglBase: EglBase,
    private val session: ManualP2PSession,
    private val advertiser: RecorderNsdAdvertiser,
    private val identity: DeviceIdentity,
    private val pairedViewerStore: PairedViewerStore,
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

    /** 今表示すべき OTP。ペアリング受付中でなければ null */
    private val _otpCode = MutableStateFlow<String?>(null)
    val otpCode: StateFlow<String?> = _otpCode.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var connection: SignalingConnection? = null
    private var signalingStarted = false
    private var currentOtp: OtpSession? = null

    suspend fun start() = cameraSession.start()

    fun stop() = cameraSession.stop()

    fun switchCamera(deviceName: String) = cameraSession.switchTo(deviceName)

    fun setZoomRatio(ratio: Float) = cameraSession.setZoomRatio(ratio)

    fun updateRotation(rotation: Int) = cameraSession.updateDeviceRotation(rotation)

    /**
     * mDNS での告知と、Viewer からの TCP 接続の待ち受けを始める。
     * 複数回呼ばれても（画面の再コンポーズなどで）1回しか始めないようにしている。
     *
     * ペアリング済みの Viewer は、OTP なしでいつでも接続できる（[handleConnectRequest]）。
     * ペアリングしていない Viewer を受け入れるには、先に [startPairing] を呼んでおく必要がある。
     */
    fun startSignaling() {
        if (signalingStarted) return
        signalingStarted = true

        viewModelScope.launch {
            try {
                val server = withContext(Dispatchers.IO) { ServerSocket(0) }
                serverSocket = server
                advertiser.advertise(deviceDisplayName(), server.localPort, currentTxtAttributes())

                while (true) {
                    _signalingStatus.value = SignalingStatus.WAITING_FOR_VIEWER
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    handleConnection(socket)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "signaling failed")
                _signalingStatus.value = SignalingStatus.FAILED
            }
        }
    }

    /**
     * 「Viewer を追加」が押されたときに呼ぶ。OTP を発行して画面に表示し、
     * mDNS の `pairing` フラグを立てて、新しい Viewer からのペアリング要求を受け付けられるようにする。
     */
    fun startPairing() {
        val otp = OtpSession()
        currentOtp = otp
        _otpCode.value = otp.code

        viewModelScope.launch {
            readvertise()
            delay(otp.remainingMillis)
            // 期限が来たときに、まだ同じ OTP が有効なら（別の OTP に置き換わっていなければ）閉じる
            if (currentOtp === otp) {
                cancelPairing()
            }
        }
    }

    /** ユーザーが自分でペアリングを取りやめたいとき用 */
    fun cancelPairing() {
        currentOtp = null
        _otpCode.value = null
        viewModelScope.launch { readvertise() }
    }

    /**
     * この中で起きた例外は、呼び出し元（[startSignaling] の `while (true)` ループ）まで
     * 伝播させてはいけない: 伝播すると外側の `catch` に捕まって signaling 全体が
     * [SignalingStatus.FAILED] になり、次の Viewer を二度と受け付けなくなってしまう
     * （切断された Viewer の `SocketException: Connection reset` だけで実際に踏んだ）。
     * なので、1接続ぶんの失敗はここで止めて、次の `accept()` に戻れるようにする。
     */
    private suspend fun handleConnection(socket: Socket) {
        val conn = socket.asSignalingConnection().also { connection = it }
        _signalingStatus.value = SignalingStatus.NEGOTIATING
        try {
            when (val first = conn.receiveOne()) {
                is SignalingMessage.PairingRequest -> handlePairingRequest(conn, first)
                is SignalingMessage.ConnectRequest -> handleConnectRequest(conn, first)
                else -> conn.send(SignalingMessage.PairingRejected("unexpected first message"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "connection with a viewer failed")
        } finally {
            // 成功して offer/answer まで進んだ場合も、相手が切断した後に来るのでそのまま閉じてよい
            conn.close()
        }
    }

    private suspend fun handlePairingRequest(conn: SignalingConnection, request: SignalingMessage.PairingRequest) {
        val otp = currentOtp
        if (otp == null || !otp.isUsable) {
            conn.send(SignalingMessage.PairingRejected("pairing is not open"))
            return
        }

        val expectedHmac = hmacSha256(otp.code, "viewer", request.viewerId, request.viewerPublicKeyBase64)
        if (!constantTimeEquals(expectedHmac, request.hmacBase64)) {
            otp.recordFailure()
            conn.send(SignalingMessage.PairingRejected("invalid code"))
            if (otp.isLocked) cancelPairing()
            return
        }

        pairedViewerStore.add(
            PairedViewer(
                viewerId = request.viewerId,
                publicKeyBase64 = request.viewerPublicKeyBase64,
                displayName = "Viewer",
                pairedAtMillis = System.currentTimeMillis(),
            ),
        )
        val responseHmac = hmacSha256(otp.code, "recorder", identity.id, identity.publicKeyBase64)
        conn.send(SignalingMessage.PairingAccepted(identity.id, identity.publicKeyBase64, responseHmac))

        currentOtp = null
        _otpCode.value = null
        readvertise()

        proceedToWebRtc(conn)
    }

    private suspend fun handleConnectRequest(conn: SignalingConnection, request: SignalingMessage.ConnectRequest) {
        val paired = pairedViewerStore.find(request.viewerId)
        if (paired == null) {
            conn.send(SignalingMessage.PairingRejected("not paired"))
            return
        }
        // TODO(M5): nonce への署名で本人確認する（今は viewerId を名乗るだけで通してしまう）
        proceedToWebRtc(conn)
    }

    private suspend fun proceedToWebRtc(conn: SignalingConnection) {
        session.resetConnection()
        val offerSdp = session.createOffer()
        conn.send(SignalingMessage.Offer(offerSdp))

        conn.incoming().collect { message ->
            when (message) {
                is SignalingMessage.Answer -> {
                    session.acceptAnswer(message.sdp)
                    _signalingStatus.value = SignalingStatus.CONNECTED
                }

                is SignalingMessage.Disconnect -> conn.close()
                else -> Unit // ここには来ないはず
            }
        }
        // ここに来るのは、TCP 接続が切れて incoming() の Flow が終わったとき
        _signalingStatus.value = SignalingStatus.DISCONNECTED
    }

    private suspend fun readvertise() {
        val port = serverSocket?.localPort ?: return
        advertiser.advertise(deviceDisplayName(), port, currentTxtAttributes())
    }

    private fun currentTxtAttributes(): Map<String, String> = mapOf(
        "id" to identity.id,
        "pairing" to if (currentOtp != null) "1" else "0",
    )

    private fun deviceDisplayName(): String = "Mimamori-${Build.MODEL}"

    override fun onCleared() {
        advertiser.stop()
        connection?.close()
        serverSocket?.let { runCatching { it.close() } }
        session.close()
    }
}
