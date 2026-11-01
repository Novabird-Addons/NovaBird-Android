package org.novabird.feature.ai.local

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.novabird.feature.search.NovaBirdIndexTrigger
import org.novabird.feature.vectors.EmbeddingModel
import org.novabird.feature.vectors.ModelFile

/** Files of the embedding model in the app's private storage (`files/models/<id>`). */
class ModelFiles(context: Context, val model: EmbeddingModel = EmbeddingModel.JINA_DE) {
    val dir = File(context.filesDir, "models/${model.id}")

    fun file(path: String) = File(dir, path)

    /** All files present with the expected size (checksums are verified when downloading). */
    fun isComplete() = model.files.all { file(it.path).length() == it.size }

    fun delete() {
        dir.deleteRecursively()
    }
}

/** State of the model download for the settings screen. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Waiting(val forWifi: Boolean) : DownloadState
    data class Running(val percent: Int) : DownloadState
    data object Failed : DownloadState
}

/**
 * Starts the model download in the background (WorkManager): on Wi-Fi only, or on any network after the user's explicit
 * OK for mobile data. Interrupted downloads continue where they stopped (HTTP Range).
 */
class ModelDownloads(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    fun start(allowMobileData: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .apply { if (allowMobileData) addTag(TAG_MOBILE) }
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    val state: Flow<DownloadState> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val info = infos.firstOrNull() ?: return@map DownloadState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                DownloadState.Waiting(forWifi = !info.inputDataMobile())

            WorkInfo.State.RUNNING -> DownloadState.Running(info.progress.getInt(KEY_PERCENT, 0))

            WorkInfo.State.FAILED -> DownloadState.Failed

            else -> DownloadState.Idle
        }
    }

    // input data isn't part of WorkInfo, so a tag says whether mobile data is allowed
    private fun WorkInfo.inputDataMobile() = tags.contains(TAG_MOBILE)

    internal companion object {
        const val WORK_NAME = "novabird-ai-model-download"
        const val KEY_PERCENT = "percent"
        const val TAG_MOBILE = "mobile-data"
    }
}

/** Downloads the model files, checks their SHA-256 and moves them in place. */
class ModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val files: ModelFiles by inject()
    private val indexTrigger: NovaBirdIndexTrigger by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = files.model
        val total = model.totalBytes
        var done = 0L
        try {
            for (f in model.files) {
                val target = files.file(f.path)
                if (target.length() != f.size) download(f, target) { bytes -> report(done + bytes, total) }
                done += f.size
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return@withContext if (runAttemptCount < MAX_ATTEMPTS &&
                e !is ChecksumException
            ) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        indexTrigger.requestIndexing()
        indexTrigger.requestVectors()
        Result.success()
    }

    private suspend fun report(done: Long, total: Long) {
        setProgress(workDataOf(ModelDownloads.KEY_PERCENT to (done * PERCENT / total).toInt()))
    }

    private suspend fun download(f: ModelFile, target: File, progress: suspend (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var have = part.length().takeIf { it < f.size } ?: 0L
        val conn = URL(files.model.fileUrl(f.path)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_OK) have = 0
            check(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) { "HTTP $status" }
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                conn.inputStream.use { input -> copy(input, out, have, progress) }
            }
        } finally {
            conn.disconnect()
        }
        check(!isStopped) { "stopped" }
        if (sha256(part) != f.sha256) {
            part.delete()
            throw ChecksumException(f.path)
        }
        check(part.renameTo(target)) { "rename" }
    }

    private suspend fun copy(input: InputStream, out: RandomAccessFile, start: Long, progress: suspend (Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var have = start
        var sinceReport = 0L
        while (!isStopped) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            sinceReport += n
            if (sinceReport >= REPORT_EVERY) {
                progress(have)
                sinceReport = 0
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class ChecksumException(path: String) : Exception("checksum $path")

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 64 * 1024
        const val REPORT_EVERY = 4L * 1024 * 1024
        const val PERCENT = 100
        const val MAX_ATTEMPTS = 5
    }
}
