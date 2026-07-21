package au.com.transpired.howietranslate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes the speech capability bundled with the app.
 *
 * Howie Translate now ships one multilingual Whisper model inside the APK. The same private
 * model recognises multiple languages, so there are no separate speech downloads and no
 * Google/Samsung speech-service dependency when Offline Whisper is selected.
 */
public final class OfflineSpeechManager {
    static final String WHISPER_ASSET_PATH = "models/ggml-tiny.bin";

    public static final class ModelInfo {
        public final String language;
        public final String displayName;
        public final String modelName;
        public final String url;
        public final String sizeLabel;

        ModelInfo(String language, String displayName) {
            this.language = language;
            this.displayName = displayName;
            this.modelName = "ggml-tiny-multilingual";
            this.url = "";
            this.sizeLabel = "built into the app";
        }
    }

    private static final Map<String, ModelInfo> MODELS;
    static {
        Map<String, ModelInfo> items = new HashMap<>();
        items.put("en", new ModelInfo("en", "English"));
        items.put("zh", new ModelInfo("zh", "Mandarin Chinese"));
        items.put("vi", new ModelInfo("vi", "Vietnamese"));
        items.put("th", new ModelInfo("th", "Thai"));
        items.put("ms", new ModelInfo("ms", "Malay"));
        items.put("yue", new ModelInfo("yue", "Cantonese (best effort)"));
        items.put("nan", new ModelInfo("nan", "Teo Chew (best effort)"));
        MODELS = Collections.unmodifiableMap(items);
    }

    public interface DownloadCallback {
        void onProgress(String message, int percent);
        void onComplete();
        void onError(String message);
    }

    /** Retained for source compatibility with the earlier Vosk implementation. */
    public interface SpeechCallback {
        void onPreparing(String message);
        void onPartial(String text);
        void onResult(String text);
        void onError(String message);
        void onTimeout();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public OfflineSpeechManager(Context context) {
        this.context = context.getApplicationContext();
        // v0.1/v0.2 used separate Vosk downloads. They are no longer needed after upgrading.
        deleteRecursively(new File(this.context.getFilesDir(), "vosk-models"));
    }

    public static ModelInfo modelInfo(String language) {
        ModelInfo info = MODELS.get(normalise(language));
        return info != null ? info : MODELS.get("en");
    }

    public boolean isInstalled(String language) {
        try (InputStream ignored = context.getAssets().open(WHISPER_ASSET_PATH)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String status(String language) {
        if (!isInstalled(language)) return "The bundled Whisper model is missing from this build.";
        return "Multilingual Whisper speech model is built in ✓";
    }

    /**
     * The Whisper library copies the bundled asset to this cache path the first time it loads.
     * Exposed only to preserve compatibility with older code.
     */
    public File modelDirectory(String language) {
        return new File(context.getCacheDir(), "ggml-tiny.bin");
    }

    public void download(String language, DownloadCallback callback) {
        mainHandler.post(() -> {
            if (isInstalled(language)) {
                callback.onProgress("Whisper is already included in the app.", 100);
                callback.onComplete();
            } else {
                callback.onError("The bundled Whisper model could not be found. Reinstall this APK.");
            }
        });
    }

    /** The bundled model is part of the APK and is removed automatically when the app is uninstalled. */
    public void deleteModel(String language) {
        // Intentionally no-op. The model is an APK asset and cannot be removed independently.
    }

    public void startListening(String language, SpeechCallback callback) {
        mainHandler.post(() -> callback.onError(
                "Speech is handled by the continuous Whisper conversation engine in this version."));
    }

    public void stop() {
        // StreamingSpeechManager owns the microphone and stop lifecycle.
    }

    public void close() {
        // No separate resources are owned here.
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static String normalise(String language) {
        return LanguageSupport.normalise(language);
    }
}
