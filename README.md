# Video Transcript for Android

A free Android transcript tool built around one simple flow:

Paste a public social-media video URL -> get the transcript -> save TXT or Markdown.

## Current architecture

This release is online-first.

1. The app opens a public web transcription service inside its own WebView and submits one user-requested link.
2. The app waits for the generated transcript and timestamped text.
3. The result is shown in the app and can be copied or saved as TXT / Markdown.
4. When the online engine fails, the app falls back to the original on-device pipeline: yt-dlp Android + Whisper.

The current online engine targets public YouTube, Instagram and TikTok links. The local backup can attempt many additional yt-dlp-supported sites, but social platforms can change their protection and availability at any time.

## Important limits

- Public links only.
- No login, private-content, DRM, bot-protection or access-control bypassing.
- Internet is required for the online-first path.
- The online service currently advertises free, no-account transcription, but third-party service availability and limits can change.
- Transcripts should be reviewed before relying on them for publication, legal, or other high-stakes use.

## Output

- transcript.txt
- transcript.md
- No audio export
- No SRT export in the Android UI

## Language

Whisper fallback uses automatic language detection and is intended for multilingual speech, including Urdu + English mixed speech.

## Device support

- Android 7.0+ (API 24)
- arm64-v8a APK
- First-run local fallback downloads the multilingual Whisper base model.
- The model file is verified with SHA-256 before use.

## Build

Open the repository in Android Studio or use the included GitHub Actions workflow.

## Main components

- Android + Kotlin + Jetpack Compose legacy screen kept in source for compatibility
- Online WebView transcription engine
- yt-dlp Android local fallback
- Whisper Android local fallback

The application does not require a paid API key.