package io.github.mtkw0127.mimamori.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 選ばれた Recorder に TCP で接続して offer/answer を自動でやり取りする（M3）。
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val session: ManualP2PSession,
    private val discoverer: ViewerNsdDiscoverer,
    val eglBase: EglBase,
) : ViewModel() {

    val discoveredRecorders: StateFlow<List<DiscoveredRecorder>> =
        discoverer.discover().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val remoteVideoTrack: StateFlow<VideoTrack?> = session.remoteVideoTrack
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = session.connectionState

    private val _status = MutableStateFlow(ViewerConnectionStatus.IDLE)
    val status: StateFlow<ViewerConnectionStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var connection: SignalingConnection? = null
    private var socket: Socket? = null

    /** 一覧から Recorder をタップしたときに呼ぶ */
    fun connect(recorder: DiscoveredRecorder) {
        if (_status.value != ViewerConnectionStatus.IDLE) return

        viewModelScope.launch {
            _status.value = ViewerConnectionStatus.CONNECTING
            try {
                val conn = withContext(Dispatchers.IO) {
                    val sock = Socket(recorder.host, recorder.port).also { socket = it }
                    sock.asSignalingConnection()
                }
                connection = conn
                _status.value = ViewerConnectionStatus.NEGOTIATING

                conn.incoming().collect { message ->
                    when (message) {
                        is SignalingMessage.Offer -> {
                            val answerSdp = session.acceptOfferAndCreateAnswer(message.sdp)
                            conn.send(SignalingMessage.Answer(answerSdp))
                            _status.value = ViewerConnectionStatus.CONNECTED
                        }

                        is SignalingMessage.Disconnect -> conn.close()
                        is SignalingMessage.Answer -> Unit // Viewer 側には来ないはず
                    }
                }
                // ここに来るのは、TCP 接続が切れて incoming() の Flow が終わったとき
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

    fun onErrorShown() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        connection?.close()
        socket?.let { runCatching { it.close() } }
        session.close()
    }
}
