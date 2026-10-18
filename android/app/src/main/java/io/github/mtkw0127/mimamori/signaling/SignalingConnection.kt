package io.github.mtkw0127.mimamori.signaling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * [SignalingMessage] を、1行1 JSON（改行区切り）の形で TCP ソケットに流す/読み出す。
 *
 * TCP は「ここまでが1メッセージ」という区切りを保証してくれないので、
 * 自分で区切り方を決める必要がある（docs/architecture.md §3.2）。ここでは
 * 「JSON の後に改行を1つ付けて送る」という、一番シンプルなルールにしている。
 * （SDP の文字列自体に改行は含まれるが、JSON の中の文字列としてエスケープされる
 * ＝ `\n` という2文字になるので、JSON 1個の中では実際の改行コードは出てこない）
 */
class SignalingConnection(private val socket: Socket) {

    private val json = Json { ignoreUnknownKeys = true }
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

    /**
     * 受信ループとは別のコルーチン（ズーム操作など）からも送るので、
     * 2つのメッセージの書き込みが混ざって1行が壊れないよう、送信は1つずつにする
     */
    private val sendMutex = Mutex()

    suspend fun send(message: SignalingMessage) {
        val line = json.encodeToString(SignalingMessage.serializer(), message)
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }
    }

    /**
     * 1行だけメッセージを受け取る。接続が切れていれば null。
     *
     * 最初の1通だけ見て分岐したいとき（[io.github.mtkw0127.mimamori.ui.recorder.RecorderViewModel]
     * の PairingRequest/ConnectRequest の振り分けなど）は、[incoming] の Flow を `.first()` で
     * 使ってはいけない: `incoming()` は [flowOn] でスレッドを分けているため、`.first()` が
     * 1件受け取ってキャンセルしようとしても、ブロッキング呼び出しである `readLine()` の途中では
     * キャンセルが効かず、次の行（本来 [acceptOfferAndCreateAnswer] 等が読むはずの offer/answer）
     * まで読み進めてしまい、取りこぼす（実機で接続が「接続中」のまま進まなくなる形で踏んだ）。
     */
    suspend fun receiveOne(): SignalingMessage? = withContext(Dispatchers.IO) {
        while (true) {
            val line = reader.readLine() ?: return@withContext null
            if (line.isBlank()) continue
            return@withContext try {
                json.decodeFromString(SignalingMessage.serializer(), line)
            } catch (e: Exception) {
                Timber.w(e, "failed to parse signaling message: %s", line)
                null
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * 届いたメッセージを順番に流す [Flow]。接続が切れる（`readLine()` が null を返す）か、
     * JSON として解釈できない行が来たら完了する。
     *
     * `readLine()` はブロッキング呼び出しなので [Dispatchers.IO] 上で行う（[flowOn]）。
     * 1件だけ読みたいときは `.first()` で使わず、[receiveOne] を使うこと（上記参照）。
     */
    fun incoming(): Flow<SignalingMessage> = flow {
        while (currentCoroutineContext().isActive) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            try {
                emit(json.decodeFromString(SignalingMessage.serializer(), line))
            } catch (e: Exception) {
                Timber.w(e, "failed to parse signaling message: %s", line)
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        runCatching { socket.close() }
    }
}

/** [Socket] を受け取る側（Recorder から見た Viewer との接続）にも、同じクラスをそのまま使う */
fun Socket.asSignalingConnection(): SignalingConnection = SignalingConnection(this)
