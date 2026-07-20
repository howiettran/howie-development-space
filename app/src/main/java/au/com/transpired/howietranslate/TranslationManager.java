package au.com.transpired.howietranslate;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class TranslationManager {
    interface Callback {
        void onStatus(String status);
        void onSuccess(String translatedText);
        void onError(String message);
    }

    interface PreparationCallback {
        void onProgress(String message);
        void onComplete();
        void onError(String message);
    }

    private final Map<String, Translator> cache = new HashMap<>();

    void translate(String source, String target, String text, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onError("Enter or dictate a phrase first.");
            return;
        }
        if (source.equals(target)) {
            callback.onSuccess(text.trim());
            return;
        }
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available in the prototype.");
            return;
        }

        Translator translator = getTranslator(sourceMl, targetMl);
        callback.onStatus("Checking the offline language models…");
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    callback.onStatus("Translating on your phone…");
                    translator.translate(text.trim())
                            .addOnSuccessListener(callback::onSuccess)
                            .addOnFailureListener(e -> callback.onError(readable(e)));
                })
                .addOnFailureListener(e -> callback.onError(
                        "The language model is not installed yet. Connect to the internet once and try again.\n\n" + readable(e)));
    }


    void preparePair(String source, String target, PreparationCallback callback) {
        if (source.equals(target)) {
            callback.onComplete();
            return;
        }
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available.");
            return;
        }
        callback.onProgress("Preparing " + source.toUpperCase() + " ⇄ " + target.toUpperCase() + " offline translation…");
        Translator translator = getTranslator(sourceMl, targetMl);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> callback.onComplete())
                .addOnFailureListener(e -> callback.onError(
                        "The offline translation models could not be prepared. " + readable(e)));
    }

    void translatePrepared(String source, String target, String text, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onError("There is no speech to translate.");
            return;
        }
        if (source.equals(target)) {
            callback.onSuccess(text.trim());
            return;
        }
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available.");
            return;
        }
        callback.onStatus("Translating…");
        getTranslator(sourceMl, targetMl).translate(text.trim())
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(e -> callback.onError(readable(e)));
    }

    void prepareAll(PreparationCallback callback) {
        String[][] pairs = {{"en", "zh"}, {"en", "vi"}, {"zh", "vi"}};
        AtomicInteger remaining = new AtomicInteger(pairs.length);
        AtomicInteger failed = new AtomicInteger(0);
        for (String[] pair : pairs) {
            callback.onProgress("Preparing " + pair[0].toUpperCase() + " ⇄ " + pair[1].toUpperCase() + "…");
            Translator translator = getTranslator(mlCode(pair[0]), mlCode(pair[1]));
            translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(unused -> {
                        if (remaining.decrementAndGet() == 0) {
                            if (failed.get() == 0) callback.onComplete();
                            else callback.onError("One or more language models could not be downloaded.");
                        }
                    })
                    .addOnFailureListener(e -> {
                        failed.incrementAndGet();
                        if (remaining.decrementAndGet() == 0)
                            callback.onError("One or more language models could not be downloaded. " + readable(e));
                    });
        }
    }

    void close() {
        for (Translator translator : cache.values()) translator.close();
        cache.clear();
    }

    private Translator getTranslator(String source, String target) {
        String key = source + ">" + target;
        Translator existing = cache.get(key);
        if (existing != null) return existing;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build();
        Translator translator = Translation.getClient(options);
        cache.put(key, translator);
        return translator;
    }

    private String mlCode(String code) {
        switch (code) {
            case "en": return TranslateLanguage.ENGLISH;
            case "zh": return TranslateLanguage.CHINESE;
            case "vi": return TranslateLanguage.VIETNAMESE;
            default: return null;
        }
    }

    private String readable(Exception e) {
        String message = e == null ? "Unknown translation error." : e.getMessage();
        return message == null || message.trim().isEmpty() ? "Translation could not be completed." : message;
    }
}
