# Howie Translate 0.5.0 Accuracy & History Beta

This build separates automatic History records from the Audio Library and tunes the bundled on-device Whisper pipeline for natural-paced speech.

## History and Audio behaviour

- Every successful typed translation is saved to **History** automatically.
- Every completed live conversation is saved to **History** automatically.
- The **Audio** page only shows conversations that the user explicitly adds by tapping **Save to Audio Library**.
- History retains Open, Edit, Delete and drag-to-reorder controls.
- A History-only live conversation retains its original recording for review but stays hidden from Audio until deliberately saved.

## Accuracy tuning

- Recognition stays locked to the selected source language.
- The end-of-speech detector waits about 800 ms before closing a phrase.
- A phrase can retain up to about 8 seconds of context.
- A 500 ms pre-roll reduces clipped beginnings.
- Very short fragments are rejected more aggressively.
- Whisper's segment allowance is increased for more natural sentence lengths.

The packaged APK continues to use the bundled multilingual Tiny model. The changes in this release improve segmentation and context handling; they do not replace the model with Base or Small.

## Existing features

- Offline English, Mandarin Chinese and Vietnamese transcription and translation
- Numbered bilingual transcripts and Chinese pinyin
- MP3 and karaoke MP4 export for Audio Library recordings
- Searchable and editable History
- Glossary organisation and editable categories

## Building

The project is an Android Gradle project. The bundled model and local AAR dependencies make the source archive large. A Java 17-compatible Android build environment and Android SDK 35 are recommended.
