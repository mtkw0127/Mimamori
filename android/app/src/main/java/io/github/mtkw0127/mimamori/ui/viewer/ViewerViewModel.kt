package io.github.mtkw0127.mimamori.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mtkw0127.mimamori.pairing.DeviceIdentity
import io.github.mtkw0127.mimamori.pairing.PairedRecorder
import io.github.mtkw0127.mimamori.pairing.PairedRecorderStore
import io.github.mtkw0127.mimamori.pairing.constantTimeEquals
import io.github.mtkw0127.mimamori.pairing.hmacSha256
import io.github.mtkw0127.mimamori.pairing.signBase64
import io.github.mtkw0127.mimamori.pairing.signedPayload
import io.github.mtkw0127.mimamori.pairing.verifySignature
import io.github.mtkw0127.mimamori.signaling.DiscoveredRecorder
import io.github.mtkw0127.mimamori.signaling.SignalingConnection
import io.github.mtkw0127.mimamori.signaling.SignalingMessage
import io.github.mtkw0127.mimamori.signaling.ViewerNsdDiscoverer
import io.github.mtkw0127.mimamori.signaling.asSignalingConnection
import io.github.mtkw0127.mimamori.webrtc.ManualP2PSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import timber.log.Timber
import java.net.Socket
import javax.inject.Inject

enum class ViewerConnectionStatus {
    IDLE,
    CONNECTING,
    NEGOTIATING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/**
 * Viewer 画面の ViewModel。mDNS で Recorder を探し（[discoveredRecorders]）、
 * 選ばれた Recorder に TCP で接続して、ペアリング済みなら [SignalingMessage.ConnectRequest]、
 * 初めてなら OTP を使った [SignalingMessage.PairingRequest] を送ってから、
 * offer/answer を自動でやり取りする（M3 + M4）。
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val session: ManualP2PSession,
    private val discoverer: ViewerNsdDiscoverer,
    val eglBase: EglBase,
    private val identity: DeviceIdentity,
    private val pairedRecorderStore: PairedRecorderStore,
) : ViewModel() {

    val discoveredRecorders: StateFlow<List<DiscoveredRecorder>> =
        discoverer.discover().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 一覧で「ペアリング済み」のバッジを出すためだけに使う、ペアリング済み Recorder の ID 集合 */
    private val _pairedRecorderIds = MutableStateFlow<Set<String>>(emptySet())
    val pairedRecorderIds: StateFlow<Set<String>> = _pairedRecorderIds.asStateFlow()

    init {
        viewModelScope.launch {
            _pairedRecorderIds.value = pairedRecorderStore.all().map { it.recorderId }.toSet()
        }
    }

    val remoteVideoTrack: StateFlow<VideoTrack?> = session.remoteVideoTrack
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = session.connectionState

    private val _status = MutableStateFlow(ViewerConnectionStatus.IDLE)
    val status: StateFlow<ViewerConnectionStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** OTP の入力待ちの Recorder。null でなければ画面に入力ダイアログを出す */
    private val _pairingTarget = MutableStateFlow<DiscoveredRecorder?>(null)
    val pairingTarget: StateFlow<DiscoveredRecorder?> = _pairingTarget.asStateFlow()

    /** Recorder から届いた、Recorder のカメラのズーム状態。まだ届いていなければ null */
    private val _zoomInfo = MutableStateFlow<SignalingMessage.ZoomInfo?>(null)
    val zoomInfo: StateFlow<SignalingMessage.ZoomInfo?> = _zoomInfo.asStateFlow()

    private var connection: SignalingConnection? = null
    private var socket: Socket? = null

    /**
     * 一覧から Recorder をタップしたときに呼ぶ。
     *
     * [DiscoveredRecorder.isPairingOpen] は mDNS の TXT レコードから読んだ時点のヒントでしかなく、
     * 当てにできない: TXT の中身だけが変わっても（`pairing` フラグが 0→1 になっても）、
     * 多くの端末では `NsdManager` がそれを「新しいサービスが見つかった」として再通知してくれず、
     * Viewer 側のキャッシュが古いまま固定されてしまうことを実機で確認した。
     * なので、ペアリング済みでなければ常に OTP 入力ダイアログを出し、
     * 実際に受け付けているかどうかの判定は Recorder 側（[SignalingMessage.PairingRequest] への応答）に任せる。
     */
    fun selectRecorder(recorder: DiscoveredRecorder) {
        if (_status.value != ViewerConnectionStatus.IDLE) return
        viewModelScope.launch {
            val paired = recorder.recorderId?.let { pairedRecorderStore.find(it) }
            if (paired != null) {
                connectAsPaired(recorder, paired)
            } else {
                _pairingTarget.value = recorder
            }
        }
    }

    fun cancelPairingDialog() {
        _pairingTarget.value = null
    }

    /** OTP 入力ダイアログで確定したときに呼ぶ */
    fun submitOtp(otp: String) {
        val recorder = _pairingTarget.value ?: return
        _pairingTarget.value = null
        pairAndConnect(recorder, otp)
    }

    /** Recorder のカメラのズーム倍率を変える。実際の倍率は Recorder から [zoomInfo] として返ってくる */
    fun setZoomRatio(ratio: Float) {
        val conn = connection ?: return
        viewModelScope.launch {
            runCatching { conn.send(SignalingMessage.SetZoom(ratio)) }
                .onFailure { Timber.w(it, "failed to send zoom request") }
        }
    }

    fun onErrorShown() {
        _errorMessage.value = null
    }

    private fun connectAsPaired(recorder: DiscoveredRecorder, paired: PairedRecorder) {
        runConnection(recorder) { conn ->
            conn.send(SignalingMessage.ConnectRequest(identity.id))

            when (val response = conn.receiveOne()) {
                is SignalingMessage.Challenge -> {
                    // mDNS で見つけた相手ではなく、ペアリングした相手本人からのチャレンジか確認してから署名する
                    check(response.recorderId == paired.recorderId) { "ペアリングした Recorder ではありません" }
                    val payload = signedPayload("challenge", response.nonceBase64, paired.recorderId, identity.id)
                    conn.send(SignalingMessage.ChallengeResponse(identity.signBase64(payload)))
                    awaitOfferAndAnswer(conn, paired.recorderId, paired.publicKeyBase64)
                }

                is SignalingMessage.PairingRejected -> {
                    if (response.reason == "not paired") {
                        // Recorder 側で破棄されている（docs/security.md §5）。こちらの登録も消す
                        forgetRecorder(paired.recorderId)
                        error("この Recorder から削除されました。もう一度ペアリングしてください")
                    }
                    error("認証に失敗しました")
                }

                else -> error("想定外の応答です")
            }
        }
    }

    private suspend fun forgetRecorder(recorderId: String) {
        pairedRecorderStore.remove(recorderId)
        _pairedRecorderIds.value = _pairedRecorderIds.value - recorderId
    }

    private fun pairAndConnect(recorder: DiscoveredRecorder, otp: String) {
        runConnection(recorder) { conn ->
            val hmac = hmacSha256(otp, "viewer", identity.id, identity.publicKeyBase64)
            conn.send(SignalingMessage.PairingRequest(identity.id, identity.publicKeyBase64, hmac))

            when (val response = conn.receiveOne()) {
                is SignalingMessage.PairingAccepted -> {
                    // Recorder も OTP を知っている証拠として HMAC を返してくる。偽 Recorder でないか確認する
                    val expected =
                        hmacSha256(otp, "recorder", response.recorderId, response.recorderPublicKeyBase64)
                    check(constantTimeEquals(expected, response.hmacBase64)) { "Recorder の確認に失敗しました" }

                    pairedRecorderStore.add(
                        PairedRecorder(
                            recorderId = response.recorderId,
                            publicKeyBase64 = response.recorderPublicKeyBase64,
                            displayName = recorder.name,
                            pairedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                    _pairedRecorderIds.value = _pairedRecorderIds.value + response.recorderId
                    awaitOfferAndAnswer(conn, response.recorderId, response.recorderPublicKeyBase64)
                }

                is SignalingMessage.PairingRejected -> error(response.reason)
                else -> error("想定外の応答です")
            }
        }
    }

    private fun runConnection(recorder: DiscoveredRecorder, block: suspend (SignalingConnection) -> Unit) {
        viewModelScope.launch {
            _status.value = ViewerConnectionStatus.CONNECTING
            try {
                val conn = withContext(Dispatchers.IO) {
                    val sock = Socket(recorder.host, recorder.port).also { socket = it }
                    sock.asSignalingConnection()
                }
                connection = conn
                _status.value = ViewerConnectionStatus.NEGOTIATING
                block(conn)
                _status.value = ViewerConnectionStatus.DISCONNECTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "failed to connect to recorder")
                _errorMessage.value = e.message ?: e.toString()
                _status.value = ViewerConnectionStatus.FAILED
            }
        }
    }

    /**
     * Recorder の公開鍵で offer の署名を検証してから、自分の秘密鍵で署名した answer を返す
     * （docs/security.md §4.2）。署名が合わなければ、映像のやり取りに進まず接続を失敗させる。
     */
    private suspend fun awaitOfferAndAnswer(
        conn: SignalingConnection,
        recorderId: String,
        recorderPublicKeyBase64: String,
    ) {
        session.resetConnection()
        _zoomInfo.value = null
        conn.incoming().collect { message ->
            when (message) {
                is SignalingMessage.Offer -> {
                    val offerPayload = signedPayload("offer", recorderId, identity.id, message.sdp)
                    check(verifySignature(recorderPublicKeyBase64, offerPayload, message.signatureBase64)) {
                        "offer の署名が正しくありません"
                    }
                    val answerSdp = session.acceptOfferAndCreateAnswer(message.sdp)
                    val answerPayload = signedPayload("answer", recorderId, identity.id, answerSdp)
                    conn.send(SignalingMessage.Answer(answerSdp, identity.signBase64(answerPayload)))
                    _status.value = ViewerConnectionStatus.CONNECTED
                }

                is SignalingMessage.ZoomInfo -> _zoomInfo.value = message

                is SignalingMessage.Revoked -> {
                    forgetRecorder(recorderId)
                    session.resetConnection()
                    _errorMessage.value = "この Recorder から削除されました"
                    conn.close()
                }

                is SignalingMessage.Disconnect -> conn.close()
                else -> Unit // ここには来ないはず
            }
        }
    }

    override fun onCleared() {
        connection?.close()
        socket?.let { runCatching { it.close() } }
        session.close()
    }
}
