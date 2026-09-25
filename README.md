# OfflineTranscript

Offline Android video/audio transcription using the multilingual Whisper model locally on the device.

## Features
- Urdu + English mixed-language transcription with Whisper language auto-detection
- Video and audio import through Android's file picker
- Android Share Sheet support from apps that share an accessible media URI
- Video audio extraction using Android MediaCodec/MediaExtractor
- Timestamped segments
- TXT and SRT export
- First-run multilingual model download with SHA-256 verification
- No transcription API key and no cloud transcription dependency after model download
- Android API 24+, arm64-v8a as required by the Whisper AAR

The app intentionally transcribes media that Android can provide as a file/URI. It does not attempt to bypass private, DRM-protected, or inaccessible social-media URLs.

## Model
Uses `dev.ffmpegkit-maintained:whisper-android:1.0.0` and the multilingual `ggml-base.bin` model.


Build workflow is configured under `.github/workflows/android.yml`.

CI verification test.
