Howie Translate v0.9.1 Saved Audio Reliability - Android source

Main changes:
- Adds Thai and Malay translation and speech selections.
- Adds best-effort Cantonese and Teo Chew modes.
- Adds timeouts so Translate and Start Live do not remain on Preparing forever.
- Downloads English, Chinese, Vietnamese, Thai and Malay translation models sequentially.
- Adds dedicated Chinese-script OCR for Mandarin, Cantonese and Teo Chew image capture.
- Opens OCR results for review and correction before translation.
- Retains separate MP3 and MP4 export-folder settings.
- Retains History, Saved conversations and linked-audio handling from v0.8.1.

Limitations:
- Cantonese and Teo Chew use the Chinese writing translation model because the bundled translation service has no separate model for either dialect.
- Teo Chew speech recognition uses a Traditional Chinese best-effort locale fallback.
- Thai typed, pasted, speech and live translation are included, but Thai-script OCR is not bundled in this build.

The source tree excludes build outputs and private signing credentials.
