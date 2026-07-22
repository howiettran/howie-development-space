# Howie Translate v0.9.2 - Durable conversation audio fix

## Conversation page
- **Save to Saved** is enabled as soon as recording stops, even if a final translation is still processing.
- Saving looks up the History row directly by database ID instead of relying on the filtered History list.
- A previous transcript-only Saved row can now be repaired when the History audio is available.
- The button reports a clear result and changes to **Saved ✓** only after the Saved row and audio copy succeed.

## History audio protection
- Completed recordings are copied into a protected `history_recordings` folder before the History row is finalised.
- Late speech and translation callbacks can no longer overwrite a valid History audio path with a blank path.
- Existing orphaned recordings are relinked to their matching History conversation where the timestamp safely matches.
- Opening History or Saved attempts a final audio-link repair before reporting that audio is unavailable.

## Saved and export reliability
- Saved receives its own verified physical audio copy using a temporary file and atomic rename.
- Existing Saved transcripts with missing audio are automatically repaired from their History source where possible.
- MP3 and MP4 export now attempts audio-link repair first and stops with a clear message if no recording exists.
- History and Saved keep independent paths, so deleting or changing one does not remove the other's audio.

## Build
- Version code: 18
- Version name: `0.9.2-durable-conversation-audio`
