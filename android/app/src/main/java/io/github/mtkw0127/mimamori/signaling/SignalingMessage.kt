package io.github.mtkw0127.mimamori.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Recorder と Viewer が、同一 LAN 内の TCP 接続（[SignalingConnection]）でやり取りするメッセージ。
 *
 * `kotlinx.serialization` の sealed class は、JSON に書き出すとき自動で
 * `"type"` フィールド（[SerialName] で指定した値）を使って種類を区別してくれる。
 * 例えば [Offer] は `{"type":"offer","sdp":"..."}` という JSON になる。
 *
 * ICE Candidate は個別には送らない（docs/architecture.md の Trickle ICE は将来の最適化）。
 * [WebRtcManualP2PSession][io.github.mtkw0127.mimamori.webrtc.WebRtcManualP2PSession] が
 * ICE 収集の完了を待ってから SDP を返す作りになっているため、[Offer]/[Answer] の `sdp` の中に
 * ICE Candidate もすでに含まれている。
 */
@Serializable
sealed class SignalingMessage {

    /** Recorder → Viewer: 映像の offer */
    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : SignalingMessage()

    /** Viewer → Recorder: offer への返事 */
    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : SignalingMessage()

    /** どちらからでも: この接続を終える合図 */
    @Serializable
    @SerialName("disconnect")
    class Disconnect : SignalingMessage() {
        override fun equals(other: Any?): Boolean = other is Disconnect
        override fun hashCode(): Int = javaClass.hashCode()
    }
}
