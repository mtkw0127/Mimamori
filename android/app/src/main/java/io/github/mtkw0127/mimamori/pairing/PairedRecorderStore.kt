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

/** Viewer がペアリング済みの Recorder 1台分として覚えておく情報 */
@Serializable
data class PairedRecorder(
    val recorderId: String,
    val publicKeyBase64: String,
    val displayName: String,
    val pairedAtMillis: Long,
)

/** [PairedViewerStore] の Viewer 版。[PairedRecorderStore] 自体に秘密情報は入らない */
@Singleton
class PairedRecorderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "paired_recorders.json")
    private val json = Json { prettyPrint = false }
    private val mutex = Mutex()

    suspend fun all(): List<PairedRecorder> = mutex.withLock { readAll() }

    suspend fun find(recorderId: String): PairedRecorder? = all().firstOrNull { it.recorderId == recorderId }

    suspend fun add(recorder: PairedRecorder) {
        mutex.withLock {
            val current = readAll().filterNot { it.recorderId == recorder.recorderId }
            writeAll(current + recorder)
        }
    }

    suspend fun remove(recorderId: String) {
        mutex.withLock {
            writeAll(readAll().filterNot { it.recorderId == recorderId })
        }
    }

    private fun readAll(): List<PairedRecorder> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<PairedRecorder>>(file.readText())
        } catch (e: Exception) {
            Timber.w(e, "failed to read paired recorders")
            emptyList()
        }
    }

    private fun writeAll(recorders: List<PairedRecorder>) {
        file.writeText(json.encodeToString(recorders))
    }
}
