# Howie Translate v0.9.0 - Multilingual stability build

## Stability fixes
- Translation and live-conversation preparation now time out with a useful error instead of spinning forever.
- Translation models download one language at a time to avoid overlapping ML Kit downloads.
- Settings shows the exact model currently being checked or downloaded.
- Translation execution also has a timeout and retry guidance.

## OCR fixes
- Added the bundled ML Kit Chinese-script recognizer.
- Chinese, Cantonese and Teo Chew image capture now use Chinese OCR instead of the Latin recognizer.
- OCR removes unrelated lines that do not match the selected script.
- Extracted text opens in an editable review window before it is placed on the Translate page.
- Full-resolution camera and uploaded images remain in use.

## New languages
- Thai
- Malay
- Cantonese (best effort; uses Chinese writing translation and Cantonese speech locale)
- Teo Chew (best effort; uses Chinese writing translation and Traditional Chinese speech fallback)

## Important limitations
- ML Kit has no separate Cantonese or Teo Chew translation model, so those modes use the Chinese model.
- Thai-script image OCR is not provided by the bundled ML Kit text-recognition libraries in this build. Typed, pasted, speech and live translation are available for Thai.
