# Howie Translate 0.10.0 Export, Accuracy and Image History Build

This Android source package consolidates the durable Conversation/History/Saved audio fixes and adds a rebuilt export pipeline, glossary-aware translation, improved live speech segmentation, and permanent original-image retention for OCR translations.

## Main changes
- MP3 export uses LAME with a Shine fallback and reports errors without closing the app.
- Karaoke MP4 uses a lower-memory rendering pipeline.
- MP3 and MP4 save to their independently selected Settings folders.
- Glossary entries protect approved names and terminology during translation.
- Live speech keeps sentence context and is less likely to clip opening or closing words.
- Capture/upload OCR images remain linked through History and Saved.
- Settings includes a separate original-image save folder.

## Building with GitHub Actions
Upload the contents of this folder to the root of the existing private GitHub repository and push to `main`. The included workflow builds and publishes `Howie-Translate-v0.10.0-test.apk` as a GitHub Actions artifact.

See `GITHUB_UPLOAD_INSTRUCTIONS.txt` for the simple upload steps and `CHANGELOG_v0.10.0.md` for technical details.
