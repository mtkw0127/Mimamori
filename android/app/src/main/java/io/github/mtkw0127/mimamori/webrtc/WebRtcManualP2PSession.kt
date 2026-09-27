package io.github.mtkw0127.mimamori.webrtc

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject

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
    @ApplicationContext private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBase: EglBase,
    private val cameraSession: LocalCameraSession,
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
