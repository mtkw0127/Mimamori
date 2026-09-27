package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [ManualP2PSession] の WebRTC 実装。
 *
 * `factory` / `eglBase` / `cameraSession` は Hilt がアプリ全体で共有しているインスタンスを渡してくる
 * （それぞれ [WebRtcModule] / [LocalCameraSession] 参照）。
 *
 * 同一 LAN 内での接続なので STUN/TURN は使わず、`RTCConfiguration` の `iceServers` は空にしている
 * （docs/architecture.md §2）。offer/answer の作成は `suspendCancellableCoroutine` で
 * `SdpObserver`/`PeerConnection.Observer` のコールバックを `suspend fun` として扱っている。
 */
class WebRtcManualP2PSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val cameraSession: LocalCameraSession,
) : ManualP2PSession {

    private var peerConnection: PeerConnection? = null
    private var peerConnectionObserver: PeerConnection.Observer? = null
    private var iceGatheringComplete: CompletableDeferred<Unit>? = null

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    override val connectionState: StateFlow<PeerConnection.PeerConnectionState> =
        _connectionState.asStateFlow()

    override suspend fun createOffer(): String {
        val connection = createPeerConnection()
        cameraSession.start()
        val videoTrack =
            cameraSession.videoTrack.value ?: error("カメラの映像を取得できませんでした")
        connection.addTrack(videoTrack)

        val mediaConstraints = MediaConstraints()
        val sdp = connection.createOfferSuspend(mediaConstraints)
        connection.setLocalDescription(sdp)
        iceGatheringComplete?.await()
        return connection.localDescription.description
    }

    private suspend fun PeerConnection.setLocalDescription(sdp: SessionDescription) {
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(message: String?) = cont.resumeWithException(
                    IllegalStateException(message)
                )

                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(message: String?) = Unit

            }
            setLocalDescription(observer, sdp)
        }
    }

    private suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription {
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(message: String?) = cont.resumeWithException(
                    IllegalStateException(message)
                )

                override fun onSetFailure(p0: String?) = Unit
            }
            createOffer(observer, constraints)
        }
    }

    override suspend fun acceptOfferAndCreateAnswer(offerSdp: String): String {
        val connection = createPeerConnection()
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp.toSdpLineEndings())
        connection.setRemoteDescriptionSuspend(offer)

        val answer = connection.createAnswerSuspend()
        connection.setLocalDescription(answer)
        iceGatheringComplete?.await()
        return connection.localDescription.description
    }

    override suspend fun acceptAnswer(answerSdp: String) {
        peerConnection?.setRemoteDescriptionSuspend(
            SessionDescription(
                SessionDescription.Type.ANSWER,
                answerSdp.toSdpLineEndings(),
            )
        )
    }

    /**
     * コピー＆ペーストを経由すると、SDP が要求する `\r\n`（CRLF）の改行が
     * クリップボードや TextField によって `\n`（LF）だけに変わってしまうことがある。
     * それを元の CRLF に戻す（既に CRLF ならそのまま）。
     *
     * さらに `ManualP2PViewModel.onApplyRemoteSdp()` が呼ぶ `.trim()` によって、
     * SDP が本来持っているべき末尾の改行が削られてしまう。末尾の改行が無いと
     * 最後の行（多くは `a=ssrc:...`）が正しく解釈されず、SDP 全体の parse が失敗して
     * 「SessionDescription is Null」というエラーになる。そのため、末尾の改行が無ければ
     * ここで必ず付け直す。
     */
    private fun String.toSdpLineEndings(): String {
        val normalized = trim().replace("\r\n", "\n").replace("\n", "\r\n")
        return if (normalized.endsWith("\r\n")) normalized else normalized + "\r\n"
    }

    override fun close() {
        peerConnection?.close()
        peerConnection = null

        cameraSession.stop()
    }

    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription {
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onSetSuccess() = Unit

                override fun onSetFailure(p0: String?) = Unit

                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { cont.resume(sdp) }
                }

                override fun onCreateFailure(p0: String?) = cont.resumeWithException(
                    IllegalStateException("Answerの作成に失敗しました")
                )
            }
            createAnswer(observer, MediaConstraints())
        }
    }

    private suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) {
        return suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)

                override fun onSetFailure(p0: String?) = cont.resumeWithException(
                    IllegalStateException("リモートから送信されたSDPを設定できませんでした, $p0")
                )

                override fun onCreateSuccess(p0: SessionDescription?) = Unit

                override fun onCreateFailure(p0: String?) = Unit
            }
            setRemoteDescription(observer, sdp)
        }
    }

    private fun createPeerConnection(): PeerConnection {
        if (peerConnection != null) return checkNotNull(peerConnection)
        iceGatheringComplete = CompletableDeferred()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val observer = object : PeerConnection.Observer {
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    _connectionState.value = newState
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Timber.d("iceConnectionState=${state?.name}")
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete?.complete(Unit)
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) = Unit

            override fun onAddStream(stream: MediaStream?) = Unit

            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(dataChannel: DataChannel?) = Unit

            override fun onRenegotiationNeeded() = Unit

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }
        }
        // native 側は observer への JNI 参照を保持するが、Java/Kotlin 側からの強参照が無いと
        // GC で回収されコールバックが届かなくなることがあるため、フィールドに保持しておく
        peerConnectionObserver = observer
        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        return checkNotNull(peerConnection)
    }
}
