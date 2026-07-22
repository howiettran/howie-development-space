# Howie Translate v0.10.0 — Export, Accuracy and Original Image History

## MP3 and MP4 export rebuild
- Replaced the fragile export path with a guarded single-worker export pipeline.
- Export failures are caught and displayed instead of closing the app.
- MP3 conversion validates the source recording and finished file before saving.
- MP3 uses the bundled LAME encoder with a Shine encoder fallback.
- MP4 generation uses fewer, smaller JPEG frames and a single video-encoding thread to reduce memory pressure.
- MP3 and MP4 continue to use their separate folders selected in Settings.
- Export completion now reports the selected destination rather than incorrectly claiming every file is in Downloads.

## Translation and recognition accuracy
- User-approved Glossary terms are protected during translation and restored exactly afterwards.
- Input spacing and Chinese output spacing are normalised before and after translation.
- Live speech retains more audio before speech begins, waits longer through natural pauses, accepts short replies, and permits longer sentence segments for better context.
- Model and translation timeouts still return the app to a usable state instead of hanging forever.

## Original OCR image retention
- Capture Image and Upload Image keep an app-protected copy of the exact original image.
- The original image is linked to the translation record in History.
- Saving the translation creates a separate protected image copy in Saved, without removing the History copy.
- History and Saved show an original-image preview with full-size open and share actions.
- Missing Saved image links can be repaired from the History source when the original still exists.
- Deleting one record does not delete an image still used by another record.

## Image save location
- Settings now includes a separate Image folder selector alongside MP3 and MP4 folders.
- Original images are also copied to the selected image folder.
- Without a custom folder, Android saves them under Pictures/Howie Translate/Translation Images.

## Data migration
- Database schema upgraded to version 4 with an `image_path` field.
- Existing conversations, recordings and Saved items remain in place.
