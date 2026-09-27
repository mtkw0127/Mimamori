package io.github.mtkw0127.mimamori.webrtc

import kotlinx.coroutines.flow.StateFlow
import org.webrtc.PeerConnection

/**
 * M2: 自動発見・シグナリング（M3）の前段階として、SDP を手動でコピー＆ペーストして P2P 接続するためのセッション。
 *
 * - Offerer（Recorder）: [createOffer] → 相手に渡す → 相手の answer を [acceptAnswer]
 * - Answerer（Viewer）: 相手の offer を [acceptOfferAndCreateAnswer] → 返り値を相手に渡す
 *
 * 手動交換を 1 往復で済ませるため、返す SDP には ICE Candidate を含めておく想定
 * （= ICE の収集完了を待ってから localDescription を返す。Trickle ICE は M3 で扱う）。
 *
 * このインターフェースは UI との接点として用意したもの。実装してみて使いにくければ自由に変えてよい。
 */
interface ManualP2PSession {
    /** 接続状態。画面にそのまま表示する */
    val connectionState: StateFlow<PeerConnection.PeerConnectionState>

    /** Offerer: offer を作り、相手に渡す SDP 文字列を返す */
    suspend fun createOffer(): String

    /** Answerer: 相手の offer を受け取り、相手に返す answer の SDP 文字列を返す */
    suspend fun acceptOfferAndCreateAnswer(offerSdp: String): String

    /** Offerer: 相手の answer を受け取る */
    suspend fun acceptAnswer(answerSdp: String)

    /** PeerConnection とカメラなどのリソースを解放する */
    fun close()
}
