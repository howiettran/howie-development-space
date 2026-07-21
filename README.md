# Howie Translate 0.9.0 Multilingual Stability Build

Howie Translate is an Android translation, live-conversation, OCR, history and saved-recording app.

## Languages

- English
- Mandarin Chinese
- Vietnamese
- Thai
- Malay
- Cantonese (best effort)
- Teo Chew (best effort)

ML Kit provides dedicated on-device translation models for English, Chinese, Vietnamese, Thai and Malay. Cantonese and Teo Chew use the Chinese writing model because ML Kit does not provide separate translation models for those dialects.

## Stability changes

- Translation-model checks and translations have timeouts so the interface cannot remain on Preparing forever.
- Settings downloads translation models sequentially, one language at a time.
- Live Conversation returns to an actionable error state when a language model cannot be prepared.
- The Settings status identifies the language currently being checked or downloaded.

## Image translation

- Camera and uploaded images are processed at full resolution.
- Mandarin Chinese, Cantonese and Teo Chew use the bundled Chinese-script OCR model.
- Latin-script languages use the bundled Latin OCR model.
- Unrelated lines that do not match the selected script are filtered out.
- OCR text is editable in a review window before it is placed into Translate.
- Thai typed, pasted and speech translation is supported. Thai-script OCR is not included in the current ML Kit OCR libraries used by this build.

## Existing features

- Google Online speech with Offline Whisper fallback
- Numbered bilingual transcripts and Mandarin pinyin
- Automatic History plus deliberate Saved conversations and recordings
- Separate MP3 and MP4 export folders
- MP3 and karaoke MP4 export
- Searchable History and glossary
- Re-translate a History conversation into another supported language

## Building

The project is an Android Gradle project. It uses Java 17, Android SDK 35 and Gradle 8.10.2. The included GitHub Actions workflow builds a debug APK and uploads it as an artifact.
