package com.offlinetranscript

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OfflineTranscriptApp(initialUri = incomingUri(intent)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun incomingUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM) else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineTranscriptApp(initialUri: Uri?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputUri by remember { mutableStateOf(initialUri) }
    var inputName by remember { mutableStateOf(initialUri?.let { displayName(context, it) } ?: "") }
    var transcript by remember { mutableStateOf("") }
    var segments by remember { mutableStateOf(emptyList<TranscriptSegment>()) }
    var busy by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(if (ModelManager.isReady(context)) "Model ready — fully offline" else "First run: model download required") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf("transcript.txt") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inputUri = uri
            inputName = displayName(context, uri)
            transcript = ""
            segments = emptyList()
            error = null
            status = "File selected"
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
                status = "Saved successfully"
            }.onFailure { error = it.message ?: "Could not save file" }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Offline Transcript", fontWeight = FontWeight.Bold)
                            Text("Urdu + English • On-device Whisper", style = MaterialTheme.typography.labelSmall)
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
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1. Video یا audio منتخب کریں", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Gallery/File Manager سے فائل منتخب کریں، یا کسی social app سے Share → Offline Transcript کریں۔")
                        Button(
                            onClick = { pick.launch(arrayOf("video/*", "audio/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (inputName.isBlank()) "Choose media" else inputName)
                        }
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. Transcribe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(status)
                        if (busy && modelProgress > 0) {
                            LinearProgressIndicator(
                                progress = { modelProgress / 100f },
                                Modifier.fillMaxWidth()
                            )
                            Text("Model download: $modelProgress%")
                        }
                        Button(
                            enabled = inputUri != null && !busy,
                            onClick = {
                                val uri = inputUri ?: return@Button
                                scope.launch {
                                    busy = true
                                    error = null
                                    try {
                                        if (!ModelManager.isReady(context)) {
                                            status = "Downloading multilingual Whisper model…"
                                            withContext(Dispatchers.IO) {
                                                ModelManager.download(context) { modelProgress = it }
                                            }
                                            modelProgress = 100
                                        }
                                        status = "Preparing audio…"
                                        val local = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                                        val mime = context.contentResolver.getType(uri).orEmpty()
                                        val audio = if (mime.startsWith("audio/") || local.extension.lowercase() in setOf("wav", "mp3", "flac")) {
                                            local
                                        } else {
                                            withContext(Dispatchers.IO) { AudioExtractor.extract(context, local) }
                                        }

                                        status = "Transcribing locally…"
                                        val handle = withContext(Dispatchers.Default) {
                                            Whisper.loadModel(context, ModelManager.modelFile(context).absolutePath)
                                        }
                                        try {
                                            val result = withContext(Dispatchers.Default) {
                                                Whisper.transcribe(
                                                    handle,
                                                    audio.absolutePath,
                                                    WhisperConfig(
                                                        language = "auto",
                                                        translate = false,
                                                        threads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)),
                                                        printTimestamps = true
                                                    )
                                                )
                                            }
                                            transcript = result.text.trim()
                                            segments = result.segments.map { TranscriptSegment(it.startMs, it.endMs, it.text) }
                                            status = "Done • ${result.segments.size} timestamped segments • ${result.processingTimeMs / 1000}s"
                                        } finally {
                                            Whisper.releaseModel(handle)
                                        }
                                    } catch (t: Throwable) {
                                        error = t.message ?: t.javaClass.simpleName
                                        status = "Transcription failed"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.GraphicEq, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start transcription")
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
                                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", transcript))
                                    status = "Transcript copied"
                                }) {
                                    Icon(Icons.Default.ContentCopy, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy")
                                }
                                OutlinedButton(onClick = {
                                    pendingExport = transcript
                                    pendingName = "transcript.txt"
                                    save.launch(pendingName)
                                }) {
                                    Icon(Icons.Default.FileDownload, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("TXT")
                                }
                                OutlinedButton(onClick = {
                                    pendingExport = Exporters.srt(context, segments).readText()
                                    pendingName = "transcript.srt"
                                    save.launch(pendingName)
                                }) {
                                    Icon(Icons.Default.FileDownload, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("SRT")
                                }
                            }
                        }
                    }
                }

                Text(
                    "Privacy: transcription runs on the phone after the model is downloaded. The app does not need an API key or cloud transcription service.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): File {
    val name = displayName(context, uri).ifBlank { "input_media" }
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val out = File(context.cacheDir, "input_${System.currentTimeMillis()}_$safe")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Could not read selected file")
    return out
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return uri.lastPathSegment ?: "media"
}
