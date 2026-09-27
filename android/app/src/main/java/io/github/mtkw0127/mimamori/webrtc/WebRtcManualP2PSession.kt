package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.PeerConnection

/**
 * TODO(M2: あなたが実装): [ManualP2PSession] の WebRTC 実装。
 *
 * 取り組む順番のヒント（詳細は docs/architecture.md §2, docs/roadmap.md M2）:
 *  1. PeerConnectionFactory の初期化（アプリ全体で 1 回）
 *  2. RTCConfiguration（同一 LAN なので iceServers は空でよい）で PeerConnection を作る
 *  3. Offerer は M1 で作った VideoTrack を addTrack する
 *  4. offer / answer の作成と setLocalDescription / setRemoteDescription
 *     （コールバック API を suspend 関数にするには suspendCancellableCoroutine が便利）
 *  5. iceGatheringState が COMPLETE になるのを待ってから localDescription を返す
 *  6. Answerer は onTrack で受け取った VideoTrack を SurfaceViewRenderer に表示する
 */
class WebRtcManualP2PSession(
    private val context: Context,
) : ManualP2PSession {

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    override val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    override suspend fun createOffer(): String {
        TODO("M2: offer を作成する")
    }

    override suspend fun acceptOfferAndCreateAnswer(offerSdp: String): String {
        TODO("M2: offer を受け取り answer を作成する")
    }

    override suspend fun acceptAnswer(answerSdp: String) {
        TODO("M2: answer を受け取る")
    }

    override fun close() {
        // TODO(M2): PeerConnection・VideoTrack・Capturer などを解放する
    }
}
