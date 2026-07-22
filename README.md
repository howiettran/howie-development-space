# Howie Translate 0.9.2 Durable Conversation Audio Build

This Android source project fixes the Conversation, History and Saved audio lifecycle.

## Main changes
- Conversation **Save to Saved** becomes available immediately after recording stops.
- History receives a protected, durable audio copy.
- Saved receives a separate verified audio copy.
- Valid audio paths are never overwritten by blank late callbacks.
- Previous transcript-only Saved rows can be repaired from their History source.
- Open, MP3 and MP4 actions attempt safe audio-link recovery before failing.

## Building with GitHub Actions
Upload the contents of this folder to the root of the existing private GitHub repository and push to `main`. The included workflow builds and publishes `Howie-Translate-v0.9.2-test.apk` as a GitHub Actions artifact.

See `GITHUB_UPLOAD_INSTRUCTIONS.txt` for the simple upload steps and `CHANGELOG_v0.9.2.md` for technical details.
