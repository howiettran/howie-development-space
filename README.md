# Howie Translate 0.10.1 Translation Startup Fix Build

This Android source package keeps the v0.10.0 audio, export, glossary and original-image improvements, and fixes the translation-model startup path that left Translate and Start Live stuck in Preparing mode.

## Main changes
- Translate explicitly prepares only the non-English ML Kit model required by the selected pair.
- Start Live uses the same corrected model path and has an independent watchdog that always resets the controls if Android fails to return a callback.
- Download all translation models no longer attempts to download English, which is built into ML Kit.
- Live phrase translation can recover if a required model is unexpectedly missing.
- MP3 export uses LAME with a Shine fallback and reports errors without closing the app.
- Karaoke MP4 uses a lower-memory rendering pipeline.
- MP3 and MP4 save to their independently selected Settings folders.
- Glossary entries protect approved names and terminology during translation.
- Live speech keeps sentence context and is less likely to clip opening or closing words.
- Capture/upload OCR images remain linked through History and Saved.
- Settings includes a separate original-image save folder.

## Building with GitHub Actions
Upload the contents of this folder to the root of the existing private GitHub repository and push to `main`. The included workflow builds and publishes `Howie-Translate-v0.10.1-test.apk` as a GitHub Actions artifact.

See `GITHUB_UPLOAD_INSTRUCTIONS.txt` for the simple upload steps and `CHANGELOG_v0.10.1.md` for technical details.
