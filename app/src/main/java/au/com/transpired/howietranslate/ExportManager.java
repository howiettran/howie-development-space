package au.com.transpired.howietranslate;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.DocumentsContract;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates user-accessible MP3 and karaoke MP4 exports entirely on the phone. */
final class ExportManager {
    interface Callback {
        void onProgress(String message, int percent);
        void onSuccess(Uri uri, String displayName);
        void onError(String message);
    }

    private static final Pattern TRANSCRIPT_LINE = Pattern.compile(
            "^\\s*(\\d+)\\.\\s*\\[([0-9:]+)]\\s*([^:]+):\\s*(.*)$");
    private static final Pattern PINYIN_LINE = Pattern.compile(
            "^\\s*(\\d+)\\.\\s*\\[([0-9:]+)]\\s*(.*)$");

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    ExportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    void close() {
        executor.shutdownNow();
    }

    void exportMp3(Models.RecordingItem item, Callback callback) {
        File input = new File(item.path == null ? "" : item.path);
        if (!input.exists()) {
            error(callback, "The original audio file could not be found.");
            return;
        }
        File workDir = createWorkDir("mp3");
        File output = new File(workDir, "audio.mp3");
        progress(callback, "Converting the recording to MP3…", 15);
        String[] arguments = {
                "-y", "-i", input.getAbsolutePath(), "-vn",
                "-codec:a", "libmp3lame", "-b:a", "128k",
                output.getAbsolutePath()
        };
        FFmpegKit.executeWithArgumentsAsync(arguments, session -> {
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.exists()) {
                String detail = session.getAllLogsAsString();
                deleteRecursively(workDir);
                error(callback, "MP3 export failed." + shortDetail(detail));
                return;
            }
            progress(callback, "Saving the MP3 to Downloads…", 88);
            try {
                String name = safeFileName(item.title, "Howie Translate Recording") + ".mp3";
                Uri uri = copyToDownloads(output, name, "audio/mpeg", true);
                deleteRecursively(workDir);
                success(callback, uri, name);
            } catch (Exception e) {
                deleteRecursively(workDir);
                error(callback, "The MP3 was created but could not be saved: " + friendly(e));
            }
        });
    }

    void exportKaraokeMp4(Models.RecordingItem item, Callback callback) {
        File input = new File(item.path == null ? "" : item.path);
        if (!input.exists()) {
            error(callback, "The original audio file could not be found.");
            return;
        }
        executor.execute(() -> {
            File workDir = createWorkDir("karaoke");
            try {
                progress(callback, "Preparing numbered karaoke subtitles…", 5);
                List<SubtitlePair> pairs = parsePairs(item);
                if (pairs.isEmpty()) {
                    throw new IOException("No timestamped transcript lines are available for this recording.");
                }
                long totalMs = item.durationMs > 0 ? item.durationMs : Math.max(1000, pairs.get(pairs.size() - 1).startMs + 4000);
                File framesDir = new File(workDir, "frames");
                if (!framesDir.mkdirs() && !framesDir.isDirectory()) throw new IOException("Could not create the video frames folder.");
                File concat = new File(workDir, "frames.txt");
                buildFrames(item, pairs, totalMs, framesDir, concat, callback);

                File output = new File(workDir, "karaoke.mp4");
                progress(callback, "Combining the highlighted text with the original audio…", 72);
                String[] arguments = {
                        "-y",
                        "-f", "concat", "-safe", "0", "-i", concat.getAbsolutePath(),
                        "-i", input.getAbsolutePath(),
                        "-map", "0:v:0", "-map", "1:a:0",
                        "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p",
                        "-fps_mode", "vfr",
                        "-c:a", "aac", "-b:a", "128k",
                        "-shortest", "-movflags", "+faststart",
                        output.getAbsolutePath()
                };
                FFmpegKit.executeWithArgumentsAsync(arguments, session -> {
                    if (!ReturnCode.isSuccess(session.getReturnCode()) || !output.exists()) {
                        String detail = session.getAllLogsAsString();
                        deleteRecursively(workDir);
                        error(callback, "Karaoke video export failed." + shortDetail(detail));
                        return;
                    }
                    progress(callback, "Saving the karaoke video to Downloads…", 92);
                    try {
                        String name = safeFileName(item.title, "Howie Translate Karaoke") + " - Karaoke.mp4";
                        Uri uri = copyToDownloads(output, name, "video/mp4", false);
                        deleteRecursively(workDir);
                        success(callback, uri, name);
                    } catch (Exception e) {
                        deleteRecursively(workDir);
                        error(callback, "The video was created but could not be saved: " + friendly(e));
                    }
                });
            } catch (Exception e) {
                deleteRecursively(workDir);
                error(callback, "Karaoke video export failed: " + friendly(e));
            }
        });
    }

    private void buildFrames(Models.RecordingItem item, List<SubtitlePair> pairs, long totalMs,
                             File framesDir, File concat, Callback callback) throws IOException {
        List<FrameSpec> specs = new ArrayList<>();
        long firstStart = Math.max(0, pairs.get(0).startMs);
        if (firstStart > 150) specs.add(new FrameSpec(null, -1, firstStart));

        for (int i = 0; i < pairs.size(); i++) {
            SubtitlePair pair = pairs.get(i);
            long end = i + 1 < pairs.size() ? pairs.get(i + 1).startMs : totalMs;
            if (end <= pair.startMs) end = pair.startMs + 1200;
            long phraseDuration = Math.max(400, end - pair.startMs);
            List<String> tokens = tokenize(pair.original, item.sourceLanguage);
            if (tokens.isEmpty()) tokens = Collections.singletonList(pair.original);
            int maxSteps = Math.max(1, (int) (phraseDuration / 90));
            int groupSize = Math.max(1, (int) Math.ceil(tokens.size() / (double) maxSteps));
            int steps = (int) Math.ceil(tokens.size() / (double) groupSize);
            long baseDuration = Math.max(55, phraseDuration / Math.max(1, steps));
            long used = 0;
            for (int step = 0; step < steps; step++) {
                int highlightTo = Math.min(tokens.size() - 1, ((step + 1) * groupSize) - 1);
                long duration = step == steps - 1 ? Math.max(55, phraseDuration - used) : baseDuration;
                used += duration;
                specs.add(new FrameSpec(pair, highlightTo, duration));
            }
        }
        if (specs.isEmpty()) throw new IOException("No subtitle frames could be generated.");

        Bitmap logo = BitmapFactory.decodeResource(context.getResources(), R.drawable.app_icon_large);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(concat), StandardCharsets.UTF_8))) {
            for (int i = 0; i < specs.size(); i++) {
                FrameSpec spec = specs.get(i);
                File image = new File(framesDir, String.format(Locale.US, "frame_%05d.png", i));
                renderFrame(image, item, spec, logo, totalMs);
                writer.write("file '");
                writer.write(image.getAbsolutePath().replace("'", "'\\''"));
                writer.write("'\n");
                writer.write(String.format(Locale.US, "duration %.3f\n", spec.durationMs / 1000.0));
                if (i % 5 == 0 || i == specs.size() - 1) {
                    int percent = 10 + (int) Math.round((i + 1) * 57.0 / specs.size());
                    progress(callback, "Creating karaoke frame " + (i + 1) + " of " + specs.size() + "…", percent);
                }
            }
            File last = new File(framesDir, String.format(Locale.US, "frame_%05d.png", specs.size() - 1));
            writer.write("file '");
            writer.write(last.getAbsolutePath().replace("'", "'\\''"));
            writer.write("'\n");
        }
        if (logo != null) logo.recycle();
    }

    private void renderFrame(File output, Models.RecordingItem item, FrameSpec spec, Bitmap logo, long totalMs) throws IOException {
        final int width = 1280;
        final int height = 720;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

        canvas.drawColor(Color.rgb(244, 248, 255));
        paint.setColor(Color.rgb(225, 237, 255));
        canvas.drawRoundRect(new RectF(24, 22, width - 24, height - 22), 38, 38, paint);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(48, 46, width - 48, height - 48), 32, 32, paint);

        if (logo != null) canvas.drawBitmap(logo, null, new RectF(72, 64, 168, 160), paint);
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        paint.setTextSize(43);
        paint.setColor(Color.rgb(19, 52, 94));
        canvas.drawText("Howie Translate", 194, 115, paint);
        paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        paint.setTextSize(23);
        paint.setColor(Color.rgb(91, 106, 128));
        canvas.drawText(item.title == null || item.title.trim().isEmpty() ? "Karaoke conversation" : item.title.trim(), 196, 148, paint);

        if (spec.pair == null) {
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextSize(54);
            paint.setColor(Color.rgb(18, 103, 215));
            drawCentered(canvas, paint, "Numbered bilingual karaoke playback", width / 2f, 310);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            paint.setTextSize(31);
            paint.setColor(Color.rgb(78, 92, 113));
            drawCentered(canvas, paint, languageName(item.sourceLanguage) + "  →  " + languageName(item.targetLanguage), width / 2f, 375);
        } else {
            SubtitlePair pair = spec.pair;
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextSize(25);
            paint.setColor(Color.rgb(18, 103, 215));
            canvas.drawText(pair.number + ". ORIGINAL  •  " + formatDuration(pair.startMs), 82, 205, paint);

            List<String> tokens = tokenize(pair.original, item.sourceLanguage);
            if (tokens.isEmpty()) tokens = Collections.singletonList(pair.original);
            drawKaraokeTokens(canvas, tokens, spec.highlightTo, 84, 252, width - 168, 51, 66);

            float nextY = 410;
            if (pair.pinyin != null && !pair.pinyin.trim().isEmpty()) {
                paint.setTypeface(Typeface.create("sans", Typeface.ITALIC));
                paint.setTextSize(29);
                paint.setColor(Color.rgb(207, 34, 58));
                nextY = drawWrapped(canvas, paint, pair.number + ". " + pair.pinyin.trim(), 84, nextY, width - 168, 39) + 25;
            }

            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            paint.setTextSize(24);
            paint.setColor(Color.rgb(217, 32, 61));
            canvas.drawText(pair.number + ". TRANSLATION", 84, nextY, paint);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            paint.setTextSize(36);
            paint.setColor(Color.rgb(32, 48, 70));
            drawWrapped(canvas, paint, pair.translation == null || pair.translation.trim().isEmpty()
                    ? "[Translation unavailable]" : pair.translation.trim(), 84, nextY + 52, width - 168, 48);
        }

        float progress = spec.pair == null ? 0f : Math.min(1f, Math.max(0f, spec.pair.startMs / (float) Math.max(1, totalMs)));
        paint.setColor(Color.rgb(220, 226, 236));
        canvas.drawRoundRect(new RectF(84, 657, width - 84, 674), 9, 9, paint);
        paint.setColor(Color.rgb(18, 103, 215));
        canvas.drawRoundRect(new RectF(84, 657, 84 + (width - 168) * progress, 674), 9, 9, paint);

        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 92, stream)) throw new IOException("Could not encode a karaoke frame.");
        } finally {
            bitmap.recycle();
        }
    }

    private void drawKaraokeTokens(Canvas canvas, List<String> tokens, int highlightedThrough,
                                    float left, float top, float maxWidth, float textSize, float lineHeight) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        paint.setTextSize(textSize);
        float x = left;
        float y = top;
        boolean chinese = tokens.size() > 1 && tokens.stream().anyMatch(this::containsHan);
        float space = paint.measureText(" ");
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            float tokenWidth = paint.measureText(token);
            float additional = chinese ? 0 : (i == 0 ? 0 : space);
            if (x + additional + tokenWidth > left + maxWidth && x > left) {
                x = left;
                y += lineHeight;
                additional = 0;
            }
            x += additional;
            if (i <= highlightedThrough) {
                paint.setColor(Color.rgb(229, 28, 53));
                RectF highlight = new RectF(x - 5, y - textSize - 9, x + tokenWidth + 5, y + 12);
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(Color.rgb(255, 233, 237));
                canvas.drawRoundRect(highlight, 8, 8, bg);
            } else {
                paint.setColor(Color.rgb(104, 116, 134));
            }
            canvas.drawText(token, x, y, paint);
            x += tokenWidth;
        }
    }

    private float drawWrapped(Canvas canvas, Paint paint, String value, float left, float top, float maxWidth, float lineHeight) {
        if (value == null) return top;
        String[] words = value.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        float y = top;
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), left, y, paint);
                line.setLength(0);
                line.append(word);
                y += lineHeight;
            } else {
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
        }
        if (line.length() > 0) canvas.drawText(line.toString(), left, y, paint);
        return y;
    }

    private void drawCentered(Canvas canvas, Paint paint, String text, float centerX, float y) {
        canvas.drawText(text, centerX - paint.measureText(text) / 2f, y, paint);
    }

    private List<SubtitlePair> parsePairs(Models.RecordingItem item) {
        Map<Integer, SubtitlePair> map = new LinkedHashMap<>();
        parseTranscript(item.transcript, true, map);
        parseTranscript(item.translation, false, map);
        Map<Integer, String> pinyin = parsePinyin(item.pinyin);
        for (Map.Entry<Integer, String> entry : pinyin.entrySet()) {
            SubtitlePair pair = map.get(entry.getKey());
            if (pair != null) pair.pinyin = entry.getValue();
        }
        List<SubtitlePair> result = new ArrayList<>(map.values());
        result.removeIf(pair -> pair.original == null || pair.original.trim().isEmpty());
        result.sort(Comparator.comparingLong((SubtitlePair p) -> p.startMs).thenComparingInt(p -> p.number));
        return result;
    }

    private void parseTranscript(String value, boolean original, Map<Integer, SubtitlePair> map) {
        if (value == null) return;
        for (String raw : value.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher matcher = TRANSCRIPT_LINE.matcher(line);
            if (!matcher.matches()) continue;
            int number = Integer.parseInt(matcher.group(1));
            SubtitlePair pair = map.computeIfAbsent(number, n -> new SubtitlePair(n));
            pair.startMs = parseDuration(matcher.group(2));
            if (original) pair.original = matcher.group(4).trim(); else pair.translation = matcher.group(4).trim();
        }
    }

    private Map<Integer, String> parsePinyin(String value) {
        Map<Integer, String> result = new HashMap<>();
        if (value == null) return result;
        for (String raw : value.split("\\r?\\n")) {
            Matcher matcher = PINYIN_LINE.matcher(raw.trim());
            if (matcher.matches()) result.put(Integer.parseInt(matcher.group(1)), matcher.group(3).trim());
        }
        return result;
    }

    private List<String> tokenize(String text, String language) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        if ("zh".equals(language) || containsHan(text)) {
            text.codePoints().forEach(cp -> {
                String token = new String(Character.toChars(cp));
                if (!token.trim().isEmpty()) result.add(token);
            });
        } else {
            for (String token : text.trim().split("\\s+")) if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private boolean containsHan(String value) {
        if (value == null) return false;
        return value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private Uri copyToDownloads(File source, String displayName, String mimeType, boolean audio) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String selectedTree = context.getSharedPreferences("howie_translate", Context.MODE_PRIVATE)
                .getString("export_tree_uri", "");
        if (selectedTree != null && !selectedTree.trim().isEmpty()) {
            Uri treeUri = Uri.parse(selectedTree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Uri destination = DocumentsContract.createDocument(resolver, parent, mimeType, displayName);
            if (destination == null) throw new IOException("The selected export folder is unavailable.");
            try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("The selected export folder could not be opened.");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
            }
            return destination;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Howie Translate");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("Android could not create the Downloads file.");
            try (OutputStream output = resolver.openOutputStream(uri); InputStream input = new FileInputStream(source)) {
                if (output == null) throw new IOException("Android could not open the Downloads file.");
                copy(input, output);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        }
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File folder = new File(downloads, "Howie Translate");
        if (!folder.exists() && !folder.mkdirs()) folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (folder == null) throw new IOException("Downloads storage is unavailable.");
        File target = uniqueFile(folder, displayName);
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        return Uri.fromFile(target);
    }

    private File uniqueFile(File folder, String displayName) {
        File file = new File(folder, displayName);
        if (!file.exists()) return file;
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : "";
        int suffix = 2;
        while (file.exists()) file = new File(folder, base + " (" + suffix++ + ")" + ext);
        return file;
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        output.flush();
    }

    private File createWorkDir(String type) {
        File root = new File(context.getCacheDir(), "exports");
        if (!root.exists()) root.mkdirs();
        File dir = new File(root, type + "_" + System.currentTimeMillis());
        dir.mkdirs();
        return dir;
    }

    private String safeFileName(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) cleaned = fallback;
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "-").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    private long parseDuration(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        String[] parts = value.trim().split(":");
        long seconds = 0;
        for (String part : parts) seconds = seconds * 60 + Long.parseLong(part);
        return seconds * 1000;
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remain = seconds % 60;
        return hours > 0 ? String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remain)
                : String.format(Locale.US, "%02d:%02d", minutes, remain);
    }

    private String languageName(String code) {
        if ("zh".equals(code)) return "中文";
        if ("vi".equals(code)) return "Tiếng Việt";
        return "English";
    }

    private String friendly(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private String shortDetail(String logs) {
        if (logs == null || logs.trim().isEmpty()) return "";
        String clean = logs.replaceAll("\\s+", " ").trim();
        if (clean.length() > 220) clean = clean.substring(clean.length() - 220);
        return "\n\n" + clean;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void progress(Callback callback, String message, int percent) {
        main.post(() -> callback.onProgress(message, Math.max(0, Math.min(100, percent))));
    }

    private void success(Callback callback, Uri uri, String displayName) {
        main.post(() -> callback.onSuccess(uri, displayName));
    }

    private void error(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    private static final class SubtitlePair {
        final int number;
        long startMs;
        String original = "";
        String translation = "";
        String pinyin = "";

        SubtitlePair(int number) { this.number = number; }
    }

    private static final class FrameSpec {
        final SubtitlePair pair;
        final int highlightTo;
        final long durationMs;

        FrameSpec(SubtitlePair pair, int highlightTo, long durationMs) {
            this.pair = pair;
            this.highlightTo = highlightTo;
            this.durationMs = durationMs;
        }
    }
}
