package au.com.transpired.howietranslate;

import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final long QUICK_MODEL_TIMEOUT_MS = 30000L;
    private static final long TRANSLATE_TIMEOUT_MS = 20000L;
    private static final long SETTINGS_MODEL_TIMEOUT_MS = 180000L;

    private final Map<String, Translator> cache = new HashMap<>();
    private final RemoteModelManager modelManager = RemoteModelManager.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AppDatabase database;

    TranslationManager(AppDatabase database) {
        this.database = database;
    }

    void translate(String source, String target, String text, Callback callback) {
        String cleanText = normaliseInput(text);
        if (cleanText.isEmpty()) {
            callback.onError("Enter or dictate a phrase first.");
            return;
        }
        if (source.equals(target)) {
            callback.onSuccess(cleanText);
            return;
        }

        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available.");
            return;
        }
        if (sourceMl.equals(targetMl)) {
            callback.onStatus("These selections share the same Chinese writing model.");
            callback.onSuccess(cleanText);
            return;
        }

        Translator translator = getTranslator(sourceMl, targetMl);
        callback.onStatus("Checking " + LanguageSupport.displayName(source) + " and "
                + LanguageSupport.displayName(target) + " offline models...");
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                callback.onError("The language models are taking too long to prepare. Open Settings, download the translation models, then try again.");
            }
        };
        mainHandler.postDelayed(timeout, QUICK_MODEL_TIMEOUT_MS);

        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    translatePreparedInternal(translator, source, target, cleanText, callback);
                })
                .addOnFailureListener(error -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onError(modelFailureMessage(source, target, error));
                });
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
        if (sourceMl.equals(targetMl)) {
            callback.onComplete();
            return;
        }

        callback.onProgress("Preparing " + LanguageSupport.displayName(source) + " and "
                + LanguageSupport.displayName(target) + " offline translation...");
        Translator translator = getTranslator(sourceMl, targetMl);
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                callback.onError("Translation model preparation timed out. Download the models from Settings and try again.");
            }
        };
        mainHandler.postDelayed(timeout, QUICK_MODEL_TIMEOUT_MS);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onComplete();
                })
                .addOnFailureListener(error -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onError(modelFailureMessage(source, target, error));
                });
    }

    void translatePrepared(String source, String target, String text, Callback callback) {
        String cleanText = normaliseInput(text);
        if (cleanText.isEmpty()) {
            callback.onError("There is no speech to translate.");
            return;
        }
        if (source.equals(target)) {
            callback.onSuccess(cleanText);
            return;
        }
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available.");
            return;
        }
        if (sourceMl.equals(targetMl)) {
            callback.onSuccess(cleanText);
            return;
        }
        translatePreparedInternal(getTranslator(sourceMl, targetMl), source, target, cleanText, callback);
    }

    void prepareAll(PreparationCallback callback) {
        String[] models = LanguageSupport.downloadableTranslationCodes();
        downloadModelSequentially(models, 0, callback);
    }

    void close() {
        for (Translator translator : cache.values()) translator.close();
        cache.clear();
    }

    private void translatePreparedInternal(Translator translator, String source, String target,
                                           String text, Callback callback) {
        GlossaryMask glossaryMask = buildGlossaryMask(source, target, text);
        callback.onStatus(glossaryMask.replacements.isEmpty()
                ? "Translating on your phone..."
                : "Translating with your glossary terms protected...");
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                callback.onError("Translation timed out. Please try a shorter phrase or split the text into paragraphs.");
            }
        };
        mainHandler.postDelayed(timeout, TRANSLATE_TIMEOUT_MS);
        translator.translate(glossaryMask.maskedText)
                .addOnSuccessListener(translated -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onSuccess(cleanTranslatedOutput(glossaryMask.restore(translated), target));
                })
                .addOnFailureListener(error -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onError("Translation could not be completed. " + readable(error));
                });
    }

    /**
     * Protects user-approved glossary terms before they are sent to ML Kit. The numeric tokens are
     * deliberately language-neutral, so names, product terms and customer terminology are restored
     * exactly after translation instead of being reworded unpredictably.
     */
    private GlossaryMask buildGlossaryMask(String source, String target, String text) {
        GlossaryMask result = new GlossaryMask(text);
        if (database == null || text == null || text.isEmpty()) return result;
        List<Models.GlossaryItem> matches = new ArrayList<>();
        try {
            for (Models.GlossaryItem item : database.getGlossary("")) {
                if (item.originalText == null || item.translatedText == null) continue;
                if (item.originalText.trim().isEmpty() || item.translatedText.trim().isEmpty()) continue;
                if (LanguageSupport.normalise(source).equals(LanguageSupport.normalise(item.sourceLanguage))
                        && LanguageSupport.normalise(target).equals(LanguageSupport.normalise(item.targetLanguage))) {
                    matches.add(item);
                } else if (LanguageSupport.normalise(source).equals(LanguageSupport.normalise(item.targetLanguage))
                        && LanguageSupport.normalise(target).equals(LanguageSupport.normalise(item.sourceLanguage))) {
                    Models.GlossaryItem reversed = new Models.GlossaryItem();
                    reversed.originalText = item.translatedText;
                    reversed.translatedText = item.originalText;
                    matches.add(reversed);
                }
            }
        } catch (RuntimeException ignored) {
            return result;
        }
        matches.sort(Comparator.comparingInt((Models.GlossaryItem i) -> i.originalText.length()).reversed());
        int tokenNumber = 91000001;
        for (Models.GlossaryItem item : matches) {
            String original = item.originalText.trim();
            if (original.length() < 2 || !containsLiteral(result.maskedText, original,
                    LanguageSupport.isChineseScript(source) || LanguageSupport.isThai(source))) continue;
            String token;
            do { token = String.valueOf(tokenNumber++); }
            while (result.maskedText.contains(token));
            String replaced = replaceLiteral(result.maskedText, original, "[" + token + "]",
                    LanguageSupport.isChineseScript(source) || LanguageSupport.isThai(source));
            if (!replaced.equals(result.maskedText)) {
                result.maskedText = replaced;
                result.replacements.put(token, item.translatedText.trim());
            }
        }
        return result;
    }

    private boolean containsLiteral(String text, String value, boolean exactCase) {
        if (text == null || value == null) return false;
        return exactCase ? text.contains(value)
                : text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private String replaceLiteral(String text, String value, String replacement, boolean exactCase) {
        int flags = exactCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(Pattern.quote(value), flags).matcher(text)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private String normaliseInput(String text) {
        if (text == null) return "";
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\u000B\\f\\r]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private String cleanTranslatedOutput(String text, String target) {
        if (text == null) return "";
        String result = text.replaceAll("[ \\t]{2,}", " ")
                .replaceAll(" *\\n *", "\n").trim();
        if (LanguageSupport.isChineseScript(target)) {
            result = result.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        }
        return result;
    }

    private static final class GlossaryMask {
        String maskedText;
        final Map<String, String> replacements = new HashMap<>();

        GlossaryMask(String maskedText) {
            this.maskedText = maskedText == null ? "" : maskedText;
        }

        String restore(String translated) {
            String result = translated == null ? "" : translated;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                String token = entry.getKey();
                result = result.replace("[" + token + "]", entry.getValue())
                        .replace("【" + token + "】", entry.getValue())
                        .replace(token, entry.getValue());
            }
            return result;
        }
    }

    private void downloadModelSequentially(String[] models, int index, PreparationCallback callback) {
        if (index >= models.length) {
            callback.onComplete();
            return;
        }
        String code = models[index];
        String mlCode = mlCode(code);
        if (mlCode == null) {
            downloadModelSequentially(models, index + 1, callback);
            return;
        }
        int number = index + 1;
        callback.onProgress("Model " + number + " of " + models.length + ": checking "
                + LanguageSupport.displayName(code) + "...");
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(mlCode).build();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                callback.onError(LanguageSupport.displayName(code)
                        + " model download timed out. Check the internet connection, Google Play Services and available storage, then retry.");
            }
        };
        mainHandler.postDelayed(timeout, SETTINGS_MODEL_TIMEOUT_MS);

        modelManager.isModelDownloaded(model)
                .addOnSuccessListener(installed -> {
                    if (finished.get()) return;
                    if (Boolean.TRUE.equals(installed)) {
                        if (!finished.compareAndSet(false, true)) return;
                        mainHandler.removeCallbacks(timeout);
                        callback.onProgress("Model " + number + " of " + models.length + ": "
                                + LanguageSupport.displayName(code) + " is already installed.");
                        downloadModelSequentially(models, index + 1, callback);
                        return;
                    }
                    callback.onProgress("Model " + number + " of " + models.length + ": downloading "
                            + LanguageSupport.displayName(code) + "...");
                    modelManager.download(model, new DownloadConditions.Builder().build())
                            .addOnSuccessListener(unused -> {
                                if (!finished.compareAndSet(false, true)) return;
                                mainHandler.removeCallbacks(timeout);
                                callback.onProgress("Model " + number + " of " + models.length + ": "
                                        + LanguageSupport.displayName(code) + " installed.");
                                downloadModelSequentially(models, index + 1, callback);
                            })
                            .addOnFailureListener(error -> {
                                if (!finished.compareAndSet(false, true)) return;
                                mainHandler.removeCallbacks(timeout);
                                callback.onError(LanguageSupport.displayName(code)
                                        + " model could not be downloaded. " + readable(error));
                            });
                })
                .addOnFailureListener(error -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    callback.onError("The app could not check the " + LanguageSupport.displayName(code)
                            + " model. " + readable(error));
                });
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
        switch (LanguageSupport.translationCode(code)) {
            case "en": return TranslateLanguage.ENGLISH;
            case "zh": return TranslateLanguage.CHINESE;
            case "vi": return TranslateLanguage.VIETNAMESE;
            case "th": return TranslateLanguage.THAI;
            case "ms": return TranslateLanguage.MALAY;
            default: return null;
        }
    }

    private String modelFailureMessage(String source, String target, Exception error) {
        return "The " + LanguageSupport.displayName(source) + " and "
                + LanguageSupport.displayName(target)
                + " models are not ready. Open Settings and tap Download all translation models. "
                + readable(error);
    }

    private String readable(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Check the internet connection, Google Play Services and free storage, then retry.";
        }
        return message.trim();
    }
}
