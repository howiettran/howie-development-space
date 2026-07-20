# Howie Translate v0.8.0 development checkpoint

Implemented in source:
- Renamed Audio navigation to Saved.
- Added Saved page wording for saved conversations and recordings.
- Added History re-translation preview to another supported language without overwriting the original.
- Added Save action on History entries, retaining linked audio where available.
- Changed History numbering so the oldest item is number 1 while preserving the chosen display order.
- Added Capture Image and Upload Image actions to Translate.
- Added on-device ML Kit OCR text extraction.
- Added configurable export folder in Settings using Android's folder picker.
- Updated MP3 and MP4 export destination handling to use the selected folder.
- Updated version to 0.8.0-saved-ocr-history.

Build status:
- Source edits completed for this checkpoint.
- APK compilation could not run in the current environment because Gradle 8.10.2 and Maven dependencies cannot be downloaded without network access.
- Audio capture during Google Online recognition still needs device-level implementation/testing because Android SpeechRecognizer owns the microphone on many devices.
