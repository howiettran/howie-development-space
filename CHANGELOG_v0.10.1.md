# Howie Translate v0.10.1 — Translation Startup Fix

## Translate page
- Replaced the hanging pair-level `downloadModelIfNeeded()` path with explicit ML Kit model preparation.
- Downloads only the non-English model required by the selected language pair.
- English is treated as ML Kit's built-in model and is never passed to `TranslateRemoteModel.Builder`.
- Shows the exact model currently being prepared and always returns either success or an actionable timeout/error.
- Reuses a prepared language pair during the current app session for faster repeat translations.
- If Android removes a model unexpectedly, the next translation automatically prepares it again instead of silently failing.

## Conversation page
- Start Live uses the same corrected model-preparation path as the Translate page.
- Added an independent preparation watchdog so the Start Live button always returns from Preparing mode, even if an Android/ML Kit task fails to call back.
- Live phrase translation can recover by preparing a missing model rather than dropping every phrase.

## Settings
- Corrected Download all translation models so it no longer tries to download English, which ML Kit documents as built in and not downloadable.
- Chinese, Vietnamese, Thai and Malay models are prepared one at a time with clear progress and a hard timeout.

## Build
- Version code: 20
- Version name: `0.10.1-translation-startup-fix`
- GitHub Actions artifact: `Howie-Translate-v0.10.1-test.apk`
