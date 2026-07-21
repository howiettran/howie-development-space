# Howie Translate v0.9.1 - Saved audio reliability fix

## Conversation page
- Fixed **Save to Saved** so it works for both audio conversations and transcript-only conversations.
- Saving now creates a new Saved record instead of changing or moving the History record.
- The button changes to **Saved ✓** after a successful save and duplicate saves are prevented.
- The Saved page is refreshed after saving.

## Audio protection
- A Saved conversation now receives its own physical copy of the audio file in the app's `saved_recordings` folder.
- The History item keeps its original audio file and path.
- Deleting one item no longer removes an audio file still referenced by another item.
- History and Saved cards now clearly show **Audio available** or **Transcript only**.
- Opening a record with missing audio now shows a clear explanation instead of silently opening text only.

## Live conversation recording
- Offline Whisper is now the default conversation engine because it guarantees a local WAV recording.
- Google Online remains available and now attempts a separate AAC/M4A sidecar recording where the phone permits concurrent microphone access.
- If the phone blocks separate audio capture, the transcript can still be saved and the app explains that Offline Whisper should be used when audio is essential.

## Build
- Version code: 17
- Version name: `0.9.1-saved-audio-fix`
