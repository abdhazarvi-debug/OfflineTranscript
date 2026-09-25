package com.offlinetranscript

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Online-first transcription engine.
 *
 * It uses the normal YTtoTranscript website flow inside a WebView.
 * The service currently documents support for public YouTube, Instagram
 * and TikTok URLs and exposes TXT/timestamped/SRT/VTT exports.
 *
 * This is intentionally limited to one foreground request at a time.
 */
class YtToTranscriptWebEngine(context: Context) {

    companion object {
        private const val HOME_URL = "https://yttotranscript.com/"
        private const val TIKTOK_URL = "https://yttotranscript.com/tiktok-transcript"
        private const val INSTAGRAM_URL = "https://yttotranscript.com/instagram-transcript"
        private const val TIMEOUT_MS = 120_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var submitted = false
    private var sourceUrl = ""
    private var startedAt = 0L

    private var progressListener: ((String) -> Unit)? = null
    private var successListener: ((String) -> Unit)? = null
    private var errorListener: ((Throwable) -> Unit)? = null

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = WebChromeClient()

        addJavascriptInterface(object {
            @JavascriptInterface
            fun progress(message: String) {
                main.post {
                    if (active) progressListener?.invoke(message)
                }
            }

            @JavascriptInterface
            fun done(text: String) {
                main.post {
                    if (!active) return@post
                    if (text.trim().length < 20) {
                        fail(IllegalStateException("The online service returned an empty transcript."))
                        return@post
                    }
                    finishSuccess(text.trim())
                }
            }

            @JavascriptInterface
            fun fail(message: String) {
                main.post {
                    if (active) {
                        fail(IllegalStateException(message.ifBlank { "Online transcription failed." }))
                    }
                }
            }
        }, "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!active) return
                if (!submitted) {
                    progressListener?.invoke("Online transcriber loaded — submitting the video link…")
                    injectSubmitScript()
                } else {
                    progressListener?.invoke("Online transcriber is preparing the transcript…")
                    injectExtractScript()
                }
            }
        }
    }

    suspend fun transcribe(
        url: String,
        onStage: (String) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        start(
            url = url,
            onStage = onStage,
            onSuccess = { text ->
                if (continuation.isActive) continuation.resume(text)
            },
            onError = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        )

        continuation.invokeOnCancellation {
            main.post { cancel() }
        }
    }

    fun cancel() {
        active = false
        progressListener = null
        successListener = null
        errorListener = null
        main.removeCallbacksAndMessages(null)
        webView.stopLoading()
    }

    fun close() {
        cancel()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    private fun start(
        url: String,
        onStage: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        main.post {
            cancel()
            sourceUrl = url.trim()
            require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
                "Please paste a valid public video URL."
            }

            active = true
            submitted = false
            startedAt = System.currentTimeMillis()
            progressListener = onStage
            successListener = onSuccess
            errorListener = onError
            onStage("Opening the free online transcript engine…")
            webView.loadUrl(platformLandingUrl(sourceUrl))
            scheduleTimeoutCheck()
        }
    }

    private fun platformLandingUrl(url: String): String {
        val host = runCatching {
            android.net.Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.")
        }.getOrDefault("")

        return when {
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> TIKTOK_URL
            host == "instagram.com" || host.endsWith(".instagram.com") -> INSTAGRAM_URL
            else -> HOME_URL
        }
    }

    private fun scheduleTimeoutCheck() {
        main.postDelayed({
            if (!active) return@postDelayed
            if (System.currentTimeMillis() - startedAt >= TIMEOUT_MS) {
                fail(
                    IllegalStateException(
                        "The online transcript service did not finish within 2 minutes."
                    )
                )
            } else {
                scheduleTimeoutCheck()
            }
        }, 2_000L)
    }

    private fun injectSubmitScript() {
        val quotedUrl = JSONObject.quote(sourceUrl)
        val script = """
            (function() {
                const sourceUrl = $quotedUrl;

                function visible(el) {
                    if (!el) return false;
                    const s = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden'
                        && r.width > 0 && r.height > 0;
                }

                function clickDismissers() {
                    const texts = ['Accept', 'I agree', 'Got it', 'Close'];
                    Array.from(document.querySelectorAll('button,[role="button"]'))
                        .filter(visible)
                        .forEach(b => {
                            const t = (b.innerText || '').trim().toLowerCase();
                            if (texts.some(x => t === x.toLowerCase())) {
                                try { b.click(); } catch (_) {}
                            }
                        });
                }

                clickDismissers();

                // Save static page text before submitting so later extraction can
                // distinguish dynamic transcript content from FAQ/marketing copy.
                window.__offlineTranscriptBaseline = clean(document.body.innerText || '');

                const controls = Array.from(document.querySelectorAll('input,textarea'))
                    .filter(visible);

                const input = controls.find(el => {
                    const meta = (
                        (el.getAttribute('placeholder') || '') + ' ' +
                        (el.getAttribute('aria-label') || '') + ' ' +
                        (el.getAttribute('name') || '')
                    ).toLowerCase();
                    return meta.includes('youtube') || meta.includes('instagram')
                        || meta.includes('tiktok') || meta.includes('video')
                        || meta.includes('url') || meta.includes('link');
                }) || controls[0];

                if (!input) {
                    AndroidBridge.fail('Could not find the video-link box on the online service.');
                    return;
                }

                try {
                    const proto = input instanceof HTMLTextAreaElement
                        ? HTMLTextAreaElement.prototype
                        : HTMLInputElement.prototype;
                    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(input, sourceUrl);
                } catch (_) {
                    input.value = sourceUrl;
                }

                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                input.dispatchEvent(new Event('blur', { bubbles: true }));

                const buttons = Array.from(
                    document.querySelectorAll('button,[role="button"],input[type="button"],input[type="submit"]')
                ).filter(visible);

                const button = buttons.find(b => {
                    const t = (
                        (b.innerText || b.value || b.getAttribute('aria-label') || '')
                    ).trim().toLowerCase();
                    return t.includes('transcribe') || t.includes('generate transcript')
                        || t === 'convert' || t.includes('start');
                });

                if (!button) {
                    AndroidBridge.fail('Could not find the Transcribe button on the online service.');
                    return;
                }

                AndroidBridge.progress('Link submitted — waiting for the transcript…');
                try { button.click(); } catch (e) {
                    AndroidBridge.fail('Could not start the online transcription request.');
                    return;
                }

                window.__offlineTranscriptSubmitted = true;

                setTimeout(function() {
                    AndroidBridge.progress('The online service is processing the video…');
                }, 1200);
            })();
        """.trimIndent()

        main.post {
            if (active) {
                submitted = true
                webView.evaluateJavascript(script, null)
                main.postDelayed({
                    if (active) injectExtractScript()
                }, 1200L)
            }
        }
    }

    private fun injectExtractScript() {
        val script = """
            (function() {
                function clean(value) {
                    return (value || '')
                        .replace(/\u00a0/g, ' ')
                        .replace(/[ \\t]+\n/g, '\n')
                        .replace(/\n{3,}/g, '\n\n')
                        .trim();
                }

                function visible(el) {
                    if (!el) return false;
                    const s = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return s.display !== 'none' && s.visibility !== 'hidden'
                        && r.width > 0 && r.height > 0;
                }

                function timestampCount(text) {
                    return (text.match(/\b(?:\d{1,2}:)?\d{1,2}:\d{2}\b/g) || []).length;
                }

                function staticCopy(text) {
                    const lower = text.toLowerCase();
                    return lower.includes('how accurate is the transcript')
                        || lower.includes('how do i transcribe')
                        || lower.includes('which platforms are supported')
                        || lower.includes('are my transcripts stored')
                        || lower.includes('why can’t some videos be transcribed')
                        || lower.includes("why can't some videos be transcribed")
                        || lower.includes('try a sample link')
                        || lower.includes('no sign-up')
                        || lower.includes('yttotranscript')
                        || lower.includes('free youtube')
                        || lower.includes('free instagram')
                        || lower.includes('free tiktok');
                }

                function resultSignals() {
                    const els = Array.from(document.querySelectorAll(
                        'button,[role="button"],a,input[type="button"],input[type="submit"]'
                    )).filter(visible);

                    return els.some(el => {
                        const t = (
                            el.innerText || el.value || el.getAttribute('aria-label') || ''
                        ).trim().toLowerCase();

                        return t === 'copy' || t.includes('copy transcript')
                            || t.includes('download txt') || t.includes('download transcript')
                            || t === 'srt' || t === 'vtt' || t.includes('export transcript');
                    });
                }

                function novelty(text) {
                    const base = String(window.__offlineTranscriptBaseline || '');
                    if (!base || !text) return text.length;

                    const baseLines = new Set(
                        base.split('\n')
                            .map(s => clean(s).toLowerCase())
                            .filter(s => s.length >= 8)
                    );

                    const lines = text.split('\n').map(s => clean(s)).filter(Boolean);
                    let novelChars = 0;
                    lines.forEach(line => {
                        if (!baseLines.has(line.toLowerCase())) novelChars += line.length;
                    });
                    return novelChars;
                }

                function score(el, text) {
                    if (!text || text.length < 60 || text.length > 60000 || !visible(el)) {
                        return -99999;
                    }

                    const meta = (
                        String(el.id || '') + ' ' +
                        String(el.className || '') + ' ' +
                        String(el.getAttribute('aria-label') || '')
                    ).toLowerCase();

                    let score = 0;
                    if (meta.includes('transcript')) score += 220;
                    if (meta.includes('transcription')) score += 140;
                    if (meta.includes('result')) score += 120;
                    if (meta.includes('output')) score += 90;
                    if (meta.includes('caption')) score += 80;
                    if (el.isContentEditable) score += 80;
                    if (el.tagName === 'TEXTAREA') score += 80;
                    if (el.tagName === 'PRE') score += 60;

                    score += Math.min(timestampCount(text), 30) * 14;
                    score += Math.min(novelty(text) / 40, 60);

                    const childCount = el.children ? el.children.length : 0;
                    score -= Math.min(childCount, 50) * 0.8;

                    if (staticCopy(text)) score -= 700;
                    if (el.closest('header,nav,footer')) score -= 150;
                    if (el === document.body) score -= 180;

                    return score;
                }

                function extractBest() {
                    const mainRoot = document.querySelector('main,article,[role="main"]') || document.body;

                    const candidates = Array.from(
                        mainRoot.querySelectorAll(
                            'textarea,pre,[contenteditable="true"],' +
                            '[id*="transcript" i],[class*="transcript" i],' +
                            '[id*="result" i],[class*="result" i],' +
                            'div,section,article,p'
                        )
                    );

                    let bestText = '';
                    let bestScore = -99999;

                    candidates.forEach(el => {
                        const text = clean(el.value || el.innerText || el.textContent || '');
                        const s = score(el, text);
                        if (s > bestScore) {
                            bestScore = s;
                            bestText = text;
                        }
                    });

                    return bestText;
                }

                function trimTranscript(text) {
                    let lines = text.split('\n').map(s => s.trim()).filter(Boolean);

                    const firstTs = lines.findIndex(line =>
                        /\b(?:\d{1,2}:)?\d{1,2}:\d{2}\b/.test(line)
                    );

                    if (firstTs >= 0 && timestampCount(text) >= 2) {
                        lines = lines.slice(firstTs);
                    }

                    const stop = lines.findIndex(line =>
                        /^(export|download|share|copy transcript|reading time|word count)$/i.test(line)
                    );
                    if (stop > 0) lines = lines.slice(0, stop);

                    return clean(lines.join('\n'));
                }

                let attempts = 0;
                const maxAttempts = 55;

                function tryExtract() {
                    if (attempts++ >= maxAttempts) {
                        AndroidBridge.fail(
                            'The online transcript was not ready before the timeout.'
                        );
                        return;
                    }

                    const text = extractBest();
                    const timestamps = timestampCount(text);
                    const novelChars = novelty(text);
                    const hasSignals = resultSignals();

                    const validTimestamped =
                        text.length >= 120 && timestamps >= 2 && novelChars >= 80;

                    const validResult =
                        text.length >= 120 && novelChars >= 120 &&
                        hasSignals && !staticCopy(text);

                    if (validTimestamped || validResult) {
                        const cleaned = trimTranscript(text);
                        if (cleaned.length >= 120 && !staticCopy(cleaned)) {
                            AndroidBridge.done(cleaned);
                            return;
                        }
                    }

                    if (attempts === 1 || attempts % 5 === 0) {
                        AndroidBridge.progress(
                            'Waiting for the online transcript… attempt ' +
                            attempts + '/' + maxAttempts
                        );
                    }

                    setTimeout(tryExtract, 2000);
                }

                tryExtract();
            })();
        """.trimIndent()

        main.post {
            if (active) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun finishSuccess(text: String) {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        val cb = successListener
        progressListener = null
        successListener = null
        errorListener = null
        cb?.invoke(text)
    }

    private fun fail(error: Throwable) {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        val cb = errorListener
        progressListener = null
        successListener = null
        errorListener = null
        cb?.invoke(error)
    }
}
