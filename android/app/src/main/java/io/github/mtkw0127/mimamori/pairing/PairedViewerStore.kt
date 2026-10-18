package io.github.mtkw0127.mimamori.pairing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Recorder がペアリング済みの Viewer 1台分として覚えておく情報（公開鍵は Base64） */
@Serializable
data class PairedViewer(
    val viewerId: String,
    val publicKeyBase64: String,
    val displayName: String,
    val pairedAtMillis: Long,
)

/**
 * Recorder 側の「ペアリング済み Viewer 一覧」。公開鍵はそもそも秘密情報ではないので、
 * 普通のファイルに JSON で保存するだけでよい（秘密鍵は [DeviceIdentity] 側で Keystore に入っている）。
 */
@Singleton
class PairedViewerStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "paired_viewers.json")
    private val json = Json { prettyPrint = false }
    private val mutex = Mutex()

    suspend fun all(): List<PairedViewer> = mutex.withLock { readAll() }

    suspend fun find(viewerId: String): PairedViewer? = all().firstOrNull { it.viewerId == viewerId }

    suspend fun add(viewer: PairedViewer) {
        mutex.withLock {
            val current = readAll().filterNot { it.viewerId == viewer.viewerId }
            writeAll(current + viewer)
        }
    }

    /** Recorder から Viewer を破棄する（M5 で使う） */
    suspend fun remove(viewerId: String) {
        mutex.withLock {
            writeAll(readAll().filterNot { it.viewerId == viewerId })
        }
    }

    private fun readAll(): List<PairedViewer> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<PairedViewer>>(file.readText())
        } catch (e: Exception) {
            Timber.w(e, "failed to read paired viewers")
            emptyList()
        }
    }

    private fun writeAll(viewers: List<PairedViewer>) {
        file.writeText(json.encodeToString(viewers))
    }
}
