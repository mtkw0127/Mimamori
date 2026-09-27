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
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TODO(M2: あなたが実装): [ManualP2PSession] の WebRTC 実装。
 *
 * `factory` / `eglBase` / `cameraSession` は Hilt がアプリ全体で共有しているインスタンスを渡してくる
 * （それぞれ [WebRtcModule] / [LocalCameraSession] 参照）。ここで新しく作り直さないこと。
 *
 * 取り組む順番のヒント（詳細は docs/architecture.md §2, docs/roadmap.md M2）:
 *  1. RTCConfiguration（同一 LAN なので iceServers は空でよい）で PeerConnection を作る
 *  2. Offerer は [cameraSession] の `start()` を呼び、`videoTrack` を `addTrack` する
 *  3. offer / answer の作成と setLocalDescription / setRemoteDescription
 *     （コールバック API を suspend 関数にするには suspendCancellableCoroutine が便利）
 *  4. iceGatheringState が COMPLETE になるのを待ってから localDescription を返す
 *  5. Answerer は onTrack で受け取った VideoTrack を SurfaceViewRenderer に表示する
 */
class WebRtcManualP2PSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val cameraSession: LocalCameraSession,
) : ManualP2PSession {

    private var peerConnection: PeerConnection? = null
    private var iceGatheringComplete: CompletableDeferred<Unit>? = null

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
        TODO("M2: offer を受け取り answer を作成する")
    }

    override suspend fun acceptAnswer(answerSdp: String) {
        TODO("M2: answer を受け取る")
    }

    override fun close() {
        peerConnection?.close()
        peerConnection = null
    }

    private fun createPeerConnection(): PeerConnection {
        iceGatheringComplete = CompletableDeferred()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        val observer = object : PeerConnection.Observer {
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    _connectionState.value = newState
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Timber.d("state=${state?.name} [PeerConnection.Observer.onIceConnectionChange]")
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionReceivingChange(p0: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete?.complete(Unit)
                }
            }

            override fun onIceCandidate(p0: IceCandidate?) = Unit

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) = Unit

            override fun onAddStream(p0: MediaStream?) = Unit

            override fun onRemoveStream(p0: MediaStream?) = Unit

            override fun onDataChannel(p0: DataChannel?) = Unit

            override fun onRenegotiationNeeded() = Unit
        }
        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        return checkNotNull(peerConnection)
    }
}
