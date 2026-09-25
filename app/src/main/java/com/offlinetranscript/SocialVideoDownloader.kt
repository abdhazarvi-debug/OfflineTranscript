package com.offlinetranscript

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadedMedia(val file: File)

object SocialVideoDownloader {
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Int, String) -> Unit
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        val normalized = url.trim()
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "Please paste a valid http/https video URL."
        }

        val workDir = File(context.cacheDir, "download_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            YtDlp.init(context)
            val outputTemplate = File(workDir, "source.%(ext)s").absolutePath
            val request = YtDlpRequest(normalized)
                .setOutputTemplate(outputTemplate)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("--no-warnings")
                // TikTok has recently rejected yt-dlp's default browser
                // impersonation on some networks. A normal browser UA lets
                // yt-dlp fall back to its native challenge flow.
                .addOption(
                    "--user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"
                )
                .addOption("-f", "bestaudio/best")

            var lastLine = ""
            val response = YtDlp.executeAsync(request) { progress, eta, line ->
                lastLine = line
                onProgress(progress.coerceIn(0f, 100f).toInt(), line.ifBlank { if (eta >= 0) "ETA ${eta}s" else "Downloading…" })
            }.get()

            if (!response.isSuccess) {
                throw IllegalStateException(lastLine.ifBlank {
                    "Could not download this public URL. The site may require login, DRM, or may not be supported."
                })
            }

            val media = workDir.listFiles()?.firstOrNull { file ->
                file.isFile && !file.name.endsWith(".part") && file.name.startsWith("source.")
            } ?: throw IllegalStateException("The site did not provide a downloadable media file.")

            DownloadedMedia(media)
        } catch (t: Throwable) {
            workDir.deleteRecursively()
            throw t
        }
    }

    fun cleanup(downloaded: DownloadedMedia) {
        downloaded.file.parentFile?.deleteRecursively()
    }
}
