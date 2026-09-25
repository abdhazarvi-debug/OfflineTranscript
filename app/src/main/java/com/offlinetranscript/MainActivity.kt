package com.offlinetranscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OfflineTranscriptApp(initialUrl = incomingUrl(intent))
        }
    }

    private fun incomingUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.startsWith("http") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineTranscriptApp(initialUrl: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var transcript by remember { mutableStateOf("") }
    var segments by remember { mutableStateOf(emptyList<TranscriptSegment>()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf("Paste a public video link") }
    var detail by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf("transcript.txt") }

    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(pendingExport.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open output file")
                stage = "Saved successfully"
            }.onFailure { error = it.message ?: "Could not save file" }
        }
    }

    fun startTranscription() {
        val sourceUrl = url.trim()
        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
            error = "Paste a valid public video URL."
            return
        }

        scope.launch {
            busy = true
            error = null
            transcript = ""
            segments = emptyList()
            progress = 0
            detail = ""

            var downloaded: DownloadedMedia? = null
            var audio: File? = null
            try {
                if (!ModelManager.isReady(context)) {
                    stage = "Downloading Whisper model (first run)"
                    withContext(Dispatchers.IO) {
                        ModelManager.download(context) { p ->
                            scope.launch { progress = p }
                        }
                    }
                    progress = 100
                }

                stage = "Downloading video/audio from link"
                progress = 0
                downloaded = SocialVideoDownloader.download(context, sourceUrl) { p, line ->
                    scope.launch {
                        progress = p
                        detail = line.takeLast(140)
                    }
                }

                stage = "Extracting audio on the phone"
                detail = "No audio file will be exported"
                progress = 0
                audio = withContext(Dispatchers.IO) {
                    AudioExtractor.extract(context, downloaded!!.file)
                }

                stage = "Transcribing locally with Whisper"
                detail = "Internet is not used for transcription"
                progress = 0

                val handle = withContext(Dispatchers.Default) {
                    Whisper.loadModel(context, ModelManager.modelFile(context).absolutePath)
                }
                try {
                    val result = withContext(Dispatchers.Default) {
                        Whisper.transcribe(
                            handle,
                            audio!!.absolutePath,
                            WhisperConfig(
                                language = "auto",
                                translate = false,
                                threads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)),
                                printTimestamps = true
                            )
                        )
                    }

                    transcript = result.text.trim()
                    segments = result.segments.map {
                        TranscriptSegment(it.startMs, it.endMs, it.text)
                    }

                    if (transcript.isBlank()) {
                        error("Whisper returned an empty transcript. The video may have no clear speech.")
                        stage = "No speech detected"
                    } else {
                        stage = "Done — transcript is ready"
                        detail = "${result.segments.size} timestamped segments"
                    }
                } finally {
                    Whisper.releaseModel(handle)
                }
            } catch (t: Throwable) {
                error = friendlyError(t)
                stage = "Could not create transcript"
            } finally {
                withContext(Dispatchers.IO) {
                    audio?.delete()
                    downloaded?.let { SocialVideoDownloader.cleanup(it) }
                }
                busy = false
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (url.isBlank() && !initialUrl.isNullOrBlank()) url = initialUrl
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Offline Transcript", fontWeight = FontWeight.Bold)
                            Text("URL → TXT / MD • 100% on-device", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).padding(18.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Video link paste کریں", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("YouTube, TikTok, Instagram, Facebook, X/Twitter, Reddit, Vimeo اور بہت سے دوسرے public links چل سکتے ہیں۔")

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !busy,
                            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Link, contentDescription = null) },
                            label = { Text("Video URL") },
                            placeholder = { Text("https://...") }
                        )

                        Text(
                            if (url.isBlank()) "Public video link required" else "Detected: ${detectPlatform(url)}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { startTranscription() },
                                enabled = !busy && url.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Get transcript")
                            }
                            OutlinedButton(
                                onClick = { url = ""; transcript = ""; segments = emptyList(); error = null },
                                enabled = !busy && (url.isNotBlank() || transcript.isNotBlank())
                            ) {
                                androidx.compose.material3.Icon(Icons.Default.Refresh, null)
                            }
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2. Processing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stage)
                        if (busy) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("$progress%", style = MaterialTheme.typography.labelSmall)
                        }
                        if (detail.isNotBlank()) {
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (error != null) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }

                if (transcript.isNotBlank()) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Transcript", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(transcript)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip.setPrimaryClip(ClipData.newPlainText("Transcript", transcript))
                                    stage = "Transcript copied"
                                }) {
                                    androidx.compose.material3.Icon(Icons.Default.ContentCopy, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy")
                                }

                                OutlinedButton(onClick = {
                                    pendingExport = transcript
                                    pendingName = "transcript.txt"
                                    save.launch(pendingName)
                                }) {
                                    androidx.compose.material3.Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("TXT")
                                }

                                OutlinedButton(onClick = {
                                    pendingExport = Exporters.markdown(sourceUrl = url.trim(), segments = segments)
                                    pendingName = "transcript.md"
                                    save.launch(pendingName)
                                }) {
                                    androidx.compose.material3.Icon(Icons.Default.Article, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("MD")
                                }
                            }
                        }
                    }
                }

                Text(
                    "Free flow: the URL downloader and Whisper engine run on the phone. No paid API, no transcription server, and no audio export.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun detectPlatform(url: String): String {
    return runCatching {
        val host = Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        when {
            "youtube.com" == host || host.endsWith(".youtube.com") || host == "youtu.be" -> "YouTube"
            host.endsWith("tiktok.com") -> "TikTok"
            host.endsWith("instagram.com") -> "Instagram"
            host.endsWith("facebook.com") || host == "fb.watch" -> "Facebook"
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> "X / Twitter"
            host.endsWith("reddit.com") -> "Reddit"
            host.endsWith("vimeo.com") -> "Vimeo"
            host.endsWith("dailymotion.com") -> "Dailymotion"
            else -> host.ifBlank { "Unknown site" }
        }
    }.getOrDefault("Unknown site")
}

private fun friendlyError(t: Throwable): String {
    val raw = (t.cause?.message ?: t.message ?: t.javaClass.simpleName).trim()
    return when {
        raw.contains("403") -> "The site rejected the download request (HTTP 403). Try another public link or a different time."
        raw.contains("login", ignoreCase = true) || raw.contains("sign in", ignoreCase = true) -> "This video requires login. The app only handles public links without bypassing access controls."
        raw.contains("DRM", ignoreCase = true) -> "This video is DRM-protected and cannot be downloaded by the app."
        raw.length > 240 -> raw.take(237) + "…"
        else -> raw.ifBlank { "The URL could not be processed." }
    }
}
