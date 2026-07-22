# Howie Translate v0.10.2 — Live Start and Model Download Fix

## Settings
- Replaced direct `RemoteModelManager` downloads with ML Kit's supported `Translator.downloadModelIfNeeded()` pair preparation.
- English is no longer incorrectly assumed to be built in; each English language pair prepares every model ML Kit requires.
- Chinese, Vietnamese, Thai and Malay preparation starts independently, so one stalled language cannot prevent all later languages from starting.
- Each pair has a hard timeout and the button always returns to a usable state with a summary of any failures.

## Conversation
- Start Live begins listening and recording immediately after microphone/speech-engine checks.
- Translation models prepare in the background and can no longer hold the Start Live button in Preparing mode.
- Final recognised phrases remain queued until translation becomes available.
- Partial preview translation waits until the pair is ready, preventing repeated duplicate download requests.
- If model preparation fails, listening, recording and History capture continue.

## Translate
- Uses the same corrected pair-level preparation method, including the English model when ML Kit requires it.
- Concurrent requests for the same pair share one preparation task instead of starting duplicate downloads.

## Build
- Version code: 21
- Version name: `0.10.2-live-start-model-fix`
- GitHub Actions artifact: `Howie-Translate-v0.10.2-test.apk`
