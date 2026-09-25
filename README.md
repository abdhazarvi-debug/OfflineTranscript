# OfflineTranscript

Free Android app for:

Paste public social-media video URL -> download media on the phone -> extract audio locally -> transcribe with Whisper locally -> save only TXT or Markdown.

## What this version does

- Paste a public video URL from YouTube, TikTok, Instagram, Facebook, X/Twitter, Reddit, Vimeo, Dailymotion, and many other yt-dlp-supported sites.
- The downloader runs inside the Android app with no paid server, no API key, and no transcription cloud service.
- Only temporary media needed for transcription is kept in the app cache.
- Temporary downloaded media is deleted after transcription.
- Export the finished transcript as transcript.txt or transcript.md.
- No audio export and no SRT export.
- Urdu + English mixed speech is transcribed with Whisper auto language detection.
- Whisper transcription runs on-device after the model is downloaded.
- Public links only. Private/login-required/DRM-protected content is not bypassed.

## Important

The URL engine is powered by the free/open-source yt-dlp Android library. It supports a very large number of sites, but no downloader can guarantee every platform forever: websites can change, require login, use DRM, or block automated requests.

The app itself does not need a paid API or hosted resolver.

## Device support

- Android 7.0+ (API 24)
- arm64-v8a APK
- First run downloads the multilingual Whisper base model from Hugging Face.
- The model is verified with SHA-256 before use.

## Build

Open the repository in Android Studio and run the app module, or use the included GitHub Actions workflow.

## Free components

- yt-dlp-android 2.0.2 — MIT
- whisper-android 1.0.0 — MIT
