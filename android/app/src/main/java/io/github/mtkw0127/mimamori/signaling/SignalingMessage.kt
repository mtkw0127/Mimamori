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

    /**
     * Viewer → Recorder: 接続してきた最初の1通。
     *
     * 初めての Viewer は [PairingRequest]、ペアリング済みの Viewer は [ConnectRequest] を送る
     * （どちらを送るかは、mDNS の TXT レコードの `id` が自分の知っている Recorder かどうかで判断する）。
     */

    /** 初めてのペアリング（docs/security.md §3.2）。OTP そのものは送らず、OTP を鍵にした HMAC だけを送る */
    @Serializable
    @SerialName("pairingRequest")
    data class PairingRequest(
        val viewerId: String,
        val viewerPublicKeyBase64: String,
        val hmacBase64: String,
    ) : SignalingMessage()

    /** Recorder → Viewer: ペアリング成功。Recorder 側の公開鍵と、相互確認用の HMAC を返す */
    @Serializable
    @SerialName("pairingAccepted")
    data class PairingAccepted(
        val recorderId: String,
        val recorderPublicKeyBase64: String,
        val hmacBase64: String,
    ) : SignalingMessage()

    /** Recorder → Viewer: ペアリング失敗（OTP が違う/期限切れ/今はペアリング受付中でない、など） */
    @Serializable
    @SerialName("pairingRejected")
    data class PairingRejected(val reason: String) : SignalingMessage()

    /** ペアリング済みの Viewer が、2回目以降に送る接続要求。OTP は不要 */
    @Serializable
    @SerialName("connectRequest")
    data class ConnectRequest(val viewerId: String) : SignalingMessage()

    /**
     * Recorder → Viewer: [ConnectRequest] への返事。本人確認のためのチャレンジ（docs/security.md §4）。
     * Viewer は `signedPayload("challenge", nonce, recorderId, viewerId)` に署名して [ChallengeResponse] を返す
     */
    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonceBase64: String, val recorderId: String) : SignalingMessage()

    /** Viewer → Recorder: [Challenge] への署名（DER 形式の ECDSA を Base64 にしたもの） */
    @Serializable
    @SerialName("challengeResponse")
    data class ChallengeResponse(val signatureBase64: String) : SignalingMessage()

    /** Recorder → Viewer: 映像の offer。Recorder の秘密鍵による署名つき（docs/security.md §4.2） */
    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String, val signatureBase64: String) : SignalingMessage()

    /** Viewer → Recorder: offer への返事。Viewer の秘密鍵による署名つき */
    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String, val signatureBase64: String) : SignalingMessage()

    /** Recorder → Viewer: この Viewer のペアリングを破棄した。受け取った Viewer は Recorder の登録を消す */
    @Serializable
    @SerialName("revoked")
    class Revoked : SignalingMessage() {
        override fun equals(other: Any?): Boolean = other is Revoked
        override fun hashCode(): Int = javaClass.hashCode()
    }

    /**
     * Recorder → Viewer: 今のカメラのズーム倍率と、変えられる範囲。
     * 接続が確立したときと、倍率やレンズが変わるたびに送る
     */
    @Serializable
    @SerialName("zoomInfo")
    data class ZoomInfo(val ratio: Float, val minRatio: Float, val maxRatio: Float) : SignalingMessage()

    /** Viewer → Recorder: Recorder のカメラのズーム倍率を変えてほしい（範囲外なら Recorder 側で丸める） */
    @Serializable
    @SerialName("setZoom")
    data class SetZoom(val ratio: Float) : SignalingMessage()

    /**
     * Recorder → Viewer: 顔検出による見守りの状態。`alerting` が true のあいだは、
     * 顔が一定時間見えていない（顔に何かがかかった、またはいなくなった）。
     * 接続が確立したときと、状態が変わるたびに送る
     */
    @Serializable
    @SerialName("faceAlert")
    data class FaceAlert(val alerting: Boolean) : SignalingMessage()

    /** どちらからでも: この接続を終える合図 */
    @Serializable
    @SerialName("disconnect")
    class Disconnect : SignalingMessage() {
        override fun equals(other: Any?): Boolean = other is Disconnect
        override fun hashCode(): Int = javaClass.hashCode()
    }
}
