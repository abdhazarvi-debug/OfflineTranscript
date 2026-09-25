package com.offlinetranscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OnlineFirstActivity : ComponentActivity() {

    private lateinit var urlEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var transcriptText: TextView
    private lateinit var getButton: Button
    private lateinit var copyButton: Button
    private lateinit var txtButton: Button
    private lateinit var mdButton: Button

    private lateinit var webEngine: YtToTranscriptWebEngine

    private var pendingExport = ""
    private var pendingName = "transcript.txt"

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(pendingExport.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open the selected file.")
            setStatus("Saved: " + pendingName, "")
        }.onFailure {
            setStatus("Save failed", it.message.orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webEngine = YtToTranscriptWebEngine(this)
        buildUi()

        val incoming = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!incoming.isNullOrBlank() && incoming.startsWith("http")) {
            urlEdit.setText(incoming.trim())
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Video Transcript"
            textSize = 26f
            setTextColor(Color.rgb(20, 20, 20))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, lpMatchWrap())

        val subtitle = TextView(this).apply {
            text = "Paste a public video URL → get TXT / MD transcript"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        root.addView(subtitle, lpMatchWrap().apply { topMargin = dp(4) })

        urlEdit = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(urlEdit, lpMatchWrap().apply {
            topMargin = dp(16)
        })

        val platformText = TextView(this).apply {
            text = "Public links only. Online engine is tried first."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        root.addView(platformText, lpMatchWrap().apply { topMargin = dp(6) })

        getButton = Button(this).apply {
            text = "Get transcript"
            setOnClickListener { startTranscription() }
        }
        root.addView(getButton, lpMatchWrap().apply { topMargin = dp(12) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, lpMatchWrap().apply { topMargin = dp(10) })

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(30, 30, 30))
        }
        root.addView(statusText, lpMatchWrap().apply { topMargin = dp(10) })

        detailText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        root.addView(detailText, lpMatchWrap())

        transcriptText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(25, 25, 25))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }

        val transcriptScroll = ScrollView(this).apply {
            addView(transcriptText, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(14) }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        copyButton = Button(this).apply {
            text = "Copy"
            isEnabled = false
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Transcript", transcriptText.text.toString())
                )
                setStatus("Transcript copied", "")
            }
        }

        txtButton = Button(this).apply {
            text = "TXT"
            isEnabled = false
            setOnClickListener {
                pendingExport = transcriptText.text.toString()
                pendingName = "transcript.txt"
                saveLauncher.launch(pendingName)
            }
        }

        mdButton = Button(this).apply {
            text = "MD"
            isEnabled = false
            setOnClickListener {
                val source = urlEdit.text.toString().trim()
                val transcript = transcriptText.text.toString()
                pendingExport = buildMarkdown(source, transcript)
                pendingName = "transcript.md"
                saveLauncher.launch(pendingName)
            }
        }

        actions.addView(copyButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(txtButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(mdButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions)

        val hiddenWebViewHost = FrameLayout(this).apply {
            addView(
                webEngine.webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(2)
                )
            )
        }
        root.addView(
            hiddenWebViewHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
            )
        )

        setContentView(root)
        setStatus("Ready", "Paste a public video URL.")
    }

    private fun startTranscription() {
        val sourceUrl = urlEdit.text.toString().trim()
        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
            setStatus("Invalid URL", "Paste a valid http/https public video link.")
            return
        }

        getButton.isEnabled = false
        urlEdit.isEnabled = false
        copyButton.isEnabled = false
        txtButton.isEnabled = false
        mdButton.isEnabled = false
        transcriptText.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 10

        lifecycleScope.launch {
            var downloaded: DownloadedMedia? = null
            var audio: File? = null
            var onlineFailure: Throwable? = null

            try {
                setStatus("Online-first transcription", "Opening the online transcript engine…")
                progress.progress = 25

                val text = try {
                    webEngine.transcribe(sourceUrl) { message ->
                        runOnUiThread {
                            setStatus(message, "One foreground public-link request")
                            progress.progress = when {
                                message.contains("submitted", true) -> 45
                                message.contains("processing", true) -> 70
                                else -> 35
                            }
                        }
                    }
                } catch (t: Throwable) {
                    onlineFailure = t
                    setStatus(
                        "Online engine did not finish",
                        "Starting the on-device backup…"
                    )

                    downloaded = try {
                        if (!ModelManager.isReady(this@OnlineFirstActivity)) {
                            setStatus(
                                "Preparing local backup",
                                "Downloading the multilingual Whisper model (first run)…"
                            )
                            withContext(Dispatchers.IO) {
                                ModelManager.download(this@OnlineFirstActivity) { p ->
                                    runOnUiThread {
                                        progress.progress = p
                                    }
                                }
                            }
                        }

                        setStatus(
                            "Local backup",
                            "Downloading media with the local URL engine…"
                        )
                        withContext(Dispatchers.IO) {
                            SocialVideoDownloader.download(
                                this@OnlineFirstActivity,
                                sourceUrl
                            ) { p, line ->
                                runOnUiThread {
                                    progress.progress = p.coerceIn(0, 100)
                                    detailText.text = line.takeLast(180)
                                }
                            }
                        }
                    } catch (downloadError: Throwable) {
                        throw CombinedTranscriptException(onlineFailure!!, downloadError)
                    }

                    setStatus("Local backup", "Extracting temporary audio…")
                    audio = withContext(Dispatchers.IO) {
                        AudioExtractor.extract(
                            this@OnlineFirstActivity,
                            downloaded!!.file
                        )
                    }

                    setStatus(
                        "Local backup",
                        "Transcribing on the phone with Whisper…"
                    )
                    progress.progress = 0

                    val handle = withContext(Dispatchers.Default) {
                        Whisper.loadModel(
                            this@OnlineFirstActivity,
                            ModelManager.modelFile(this@OnlineFirstActivity).absolutePath
                        )
                    }

                    try {
                        val result = withContext(Dispatchers.Default) {
                            Whisper.transcribe(
                                handle,
                                audio!!.absolutePath,
                                WhisperConfig(
                                    language = "auto",
                                    translate = false,
                                    threads = maxOf(
                                        2,
                                        Runtime.getRuntime().availableProcessors().coerceAtMost(6)
                                    ),
                                    printTimestamps = true
                                )
                            )
                        }

                        val localText = result.text.trim()
                        if (localText.isBlank()) {
                            throw IllegalStateException("Whisper returned an empty transcript.")
                        }

                        if (onlineFailure != null) {
                            setStatus(
                                "Transcript ready",
                                "Online engine failed first; local Whisper backup succeeded."
                            )
                        }
                        localText
                    } finally {
                        Whisper.releaseModel(handle)
                    }
                }

                showTranscript(text, sourceUrl)
                progress.progress = 100
            } catch (t: Throwable) {
                setStatus(
                    "Could not create transcript",
                    friendlyError(t)
                )
            } finally {
                withContext(Dispatchers.IO) {
                    audio?.delete()
                    downloaded?.let { SocialVideoDownloader.cleanup(it) }
                }
                getButton.isEnabled = true
                urlEdit.isEnabled = true
            }
        }
    }

    private fun showTranscript(text: String, sourceUrl: String) {
        transcriptText.text = text.trim()
        transcriptText.visibility = View.VISIBLE
        copyButton.isEnabled = true
        txtButton.isEnabled = true
        mdButton.isEnabled = true

        if (!statusText.text.toString().contains("Transcript ready")) {
            setStatus("Transcript ready", detectPlatform(sourceUrl))
        }
    }

    private fun setStatus(main: String, detail: String) {
        statusText.text = main
        detailText.text = detail
    }

    private fun buildMarkdown(sourceUrl: String, transcript: String): String {
        val parsed = parseTimestampedSegments(transcript)
        if (parsed.isNotEmpty()) {
            return Exporters.markdown(sourceUrl, parsed)
        }

        return "# Transcript\\n\\n" +
            "Source: " + sourceUrl + "\\n\\n" +
            transcript.trim() + "\\n"
    }

    private fun parseTimestampedSegments(text: String): List<TranscriptSegment> {
        val regex = Regex("""^\s*\[?(\d{1,2}:)?(\d{1,2}):(\d{2})\]?\s*(.*)$""")
        val items = text.lineSequence().mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val hours = match.groupValues[1].removeSuffix(":").toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val seconds = match.groupValues[3].toLongOrNull() ?: 0L
            val body = match.groupValues[4].trim()
            if (body.isBlank()) null else TranscriptSegment(
                startMs = (hours * 3600L + minutes * 60L + seconds) * 1000L,
                endMs = 0L,
                text = body
            )
        }.toMutableList()

        if (items.isEmpty()) return emptyList()

        for (i in items.indices) {
            items[i] = items[i].copy(
                endMs = if (i + 1 < items.size) {
                    items[i + 1].startMs
                } else {
                    items[i].startMs + 1_000L
                }
            )
        }
        return items
    }

    private fun detectPlatform(url: String): String {
        return runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
            when {
                host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "YouTube"
                host.endsWith("tiktok.com") -> "TikTok"
                host.endsWith("instagram.com") -> "Instagram"
                host.endsWith("facebook.com") || host == "fb.watch" -> "Facebook"
                host == "x.com" || host.endsWith(".x.com") ||
                    host == "twitter.com" || host.endsWith(".twitter.com") -> "X / Twitter"
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
            t is CombinedTranscriptException ->
                "Online: " + friendlyError(t.online) +
                    " | Local backup: " + friendlyError(t.local)
            raw.contains("403") ->
                "The site rejected the request (HTTP 403). Try another public link."
            raw.contains("login", true) || raw.contains("sign in", true) ->
                "This video requires login. The app does not bypass access controls."
            raw.contains("DRM", true) ->
                "This video is DRM-protected and cannot be processed."
            raw.length > 300 ->
                raw.take(297) + "…"
            else ->
                raw.ifBlank { "The URL could not be processed." }
        }
    }

    private fun lpMatchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webEngine.close()
        super.onDestroy()
    }
}

private class CombinedTranscriptException(
    val online: Throwable,
    val local: Throwable
) : IllegalStateException("Both transcription paths failed.")
