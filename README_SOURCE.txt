Howie Translate v0.6.1 Continuous Google Fix - Android source

This build replaces the unreliable API 33+ injected-audio pathway used in v0.6.0.

Key changes:
- Google Online now lets Android SpeechRecognizer own the microphone directly.
- The app automatically starts a new phrase session after every result, no-match, timeout or temporary service interruption.
- The conversation continues until Stop is pressed; there is no app-level 10-second recording limit.
- Start/Stop remains controlled by the IDLE/PREPARING/RECORDING/STOPPING state machine.
- Final recognised phrases are sent to the existing ML Kit translation pipeline immediately.
- Google Online saves transcripts automatically to History.
- Because the online recognizer owns the microphone, a reusable original audio file is not guaranteed in this mode. Offline Whisper remains available when source-audio retention is essential.

The source tree excludes build outputs from the distribution ZIP. Signing credentials must be kept private.
