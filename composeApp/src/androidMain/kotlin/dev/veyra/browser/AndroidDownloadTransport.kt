package dev.veyra.browser

import android.content.Context
import android.os.Environment
import dev.veyra.browser.download.DownloadTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Application context holder for platform services that need it (currently only the download
 * destination directory). Initialized from MainActivity before any composition runs.
 */
internal object AndroidAppContext {
    lateinit var application: Context
        private set

    fun initialize(context: Context) {
        application = context.applicationContext
    }
}

/** Writes downloads into the app-specific external Downloads directory. */
internal class AndroidDownloadFileSinkFactory(private val root: File) : DownloadFileSinkFactory {

    override fun create(suggestedFileName: String?): DownloadFileSink {
        val directory = root.apply { mkdirs() }
        val file = uniqueFile(directory, sanitizeFileName(suggestedFileName))
        return AndroidDownloadFileSink(file)
    }

    private fun sanitizeFileName(raw: String?): String {
        val cleaned = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filterNot { character -> character in "/\\:%*?\"<>|" || character.isISOControl() }
            ?.trim()
            .orEmpty()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            "download"
        } else {
            cleaned.take(MAX_FILE_NAME_LENGTH)
        }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        var index = 1
        while (candidate.exists()) {
            val base = fileName.substringBeforeLast('.', fileName)
            val extension = fileName.substringAfterLast('.', "")
            candidate = if (extension.isEmpty() || extension == fileName) {
                File(directory, "$base ($index)")
            } else {
                File(directory, "$base ($index).$extension")
            }
            index++
        }
        return candidate
    }

    private companion object {
        const val MAX_FILE_NAME_LENGTH = 120
    }
}

private class AndroidDownloadFileSink(private val file: File) : DownloadFileSink {

    private val stream = FileOutputStream(file)

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) { stream.write(bytes) }
    }

    override suspend fun finish(): String {
        withContext(Dispatchers.IO) {
            stream.flush()
            stream.close()
        }
        return file.absolutePath
    }

    override suspend fun abort() {
        withContext(Dispatchers.IO) {
            runCatching { stream.close() }
            file.delete()
        }
    }
}

internal actual fun createPlatformDownloadTransport(): DownloadTransport? {
    val root = File(
        AndroidAppContext.application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        "veyra",
    )
    return KtorDownloadTransport(
        // expectSuccess = false: non-2xx statuses are mapped to failed download tasks here.
        client = HttpClient(OkHttp) { expectSuccess = false },
        sinkFactory = AndroidDownloadFileSinkFactory(root),
    )
}
