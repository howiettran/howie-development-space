package au.com.transpired.howietranslate;

import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Translation models are about 30 MB each. A 30-second timeout was too short on slower
    // mobile connections and left the UI appearing permanently stuck when the ML Kit task did
    // not return. Every model operation now has a visible status and a hard upper limit.
    private static final long PAIR_MODEL_TIMEOUT_MS = 90000L;
    private static final long TRANSLATE_TIMEOUT_MS = 30000L;
    private static final long SETTINGS_MODEL_TIMEOUT_MS = 120000L;

    private final Map<String, Translator> cache = new HashMap<>();
    private final Set<String> readyPairs = Collections.synchronizedSet(new HashSet<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<PreparationCallback>> pendingPreparations = new HashMap<>();
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

        String pair = pairKey(sourceMl, targetMl);
        if (readyPairs.contains(pair)) {
            translatePreparedInternal(getTranslator(sourceMl, targetMl), source, target, cleanText, callback);
            return;
        }

        ensurePairModels(source, target, new PreparationCallback() {
            @Override public void onProgress(String message) {
                callback.onStatus(message);
            }

            @Override public void onComplete() {
                readyPairs.add(pair);
                translatePreparedInternal(getTranslator(sourceMl, targetMl), source, target, cleanText, callback);
            }

            @Override public void onError(String message) {
                readyPairs.remove(pair);
                callback.onError(message);
            }
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

        String pair = pairKey(sourceMl, targetMl);
        if (readyPairs.contains(pair)) {
            callback.onProgress("Translation models are ready.");
            callback.onComplete();
            return;
        }

        ensurePairModels(source, target, new PreparationCallback() {
            @Override public void onProgress(String message) {
                callback.onProgress(message);
            }

            @Override public void onComplete() {
                readyPairs.add(pair);
                callback.onComplete();
            }

            @Override public void onError(String message) {
                readyPairs.remove(pair);
                callback.onError(message);
            }
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

        String pair = pairKey(sourceMl, targetMl);
        if (!readyPairs.contains(pair)) {
            // A live session should normally prepare its pair before listening starts. If Android
            // cleared a model or the preparation callback was interrupted, recover instead of
            // silently dropping every live phrase.
            translate(source, target, cleanText, callback);
            return;
        }
        translatePreparedInternal(getTranslator(sourceMl, targetMl), source, target, cleanText, callback);
    }

    void prepareAll(PreparationCallback callback) {
        String[] models = LanguageSupport.downloadableTranslationCodes();
        if (models.length == 0) {
            callback.onComplete();
            return;
        }

        // Prepare every English pair independently. This avoids the old behaviour where one
        // stalled model prevented all later models from even starting. Translator.downloadModelIfNeeded
        // is ML Kit's supported pair-level API and downloads every model required by that pair,
        // including English when the device does not already have it.
        AtomicInteger remaining = new AtomicInteger(models.length);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < models.length; i++) {
            final int position = i + 1;
            final String target = models[i];
            final String display = LanguageSupport.displayName(target);
            callback.onProgress("Starting model " + position + " of " + models.length + ": " + display + "…");
            preparePair("en", target, new PreparationCallback() {
                @Override public void onProgress(String message) {
                    callback.onProgress("Model " + position + " of " + models.length + " (" + display + "): " + message);
                }

                @Override public void onComplete() {
                    callback.onProgress("Model " + position + " of " + models.length + ": " + display + " is ready ✓");
                    finishPrepareAllAttempt(remaining, failures, callback);
                }

                @Override public void onError(String message) {
                    failures.add(display + ": " + message);
                    callback.onProgress("Model " + position + " of " + models.length + ": " + display + " could not be prepared. Continuing…");
                    finishPrepareAllAttempt(remaining, failures, callback);
                }
            });
        }
    }

    boolean isPairReady(String source, String target) {
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) return false;
        if (sourceMl.equals(targetMl)) return true;
        return readyPairs.contains(pairKey(sourceMl, targetMl));
    }

    private void finishPrepareAllAttempt(AtomicInteger remaining, List<String> failures,
                                         PreparationCallback callback) {
        if (remaining.decrementAndGet() != 0) return;
        if (failures.isEmpty()) {
            callback.onComplete();
            return;
        }
        StringBuilder message = new StringBuilder("The model check finished, but some languages were not ready:\n\n");
        for (String failure : failures) message.append("• ").append(failure).append('\n');
        message.append("\nThe button has been reset. You can retry while connected to the internet.");
        callback.onError(message.toString().trim());
    }

    void close() {
        for (Translator translator : cache.values()) translator.close();
        cache.clear();
        readyPairs.clear();
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
                    readyPairs.remove(pairKey(mlCode(source), mlCode(target)));
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

    /**
     * Prepares a complete source/target pair using ML Kit's Translator API. Earlier builds used
     * RemoteModelManager and skipped English, which could leave a pair permanently waiting or
     * missing one of the files that Translator actually requires. Concurrent requests for the
     * same pair are coalesced so live captions cannot launch repeated downloads.
     */
    private void ensurePairModels(String source, String target, PreparationCallback callback) {
        String sourceMl = mlCode(source);
        String targetMl = mlCode(target);
        if (sourceMl == null || targetMl == null) {
            callback.onError("This language pair is not available.");
            return;
        }
        if (sourceMl.equals(targetMl)) {
            callback.onProgress("Translation models are ready.");
            callback.onComplete();
            return;
        }

        String pair = pairKey(sourceMl, targetMl);
        if (readyPairs.contains(pair)) {
            callback.onProgress("Translation models are ready.");
            callback.onComplete();
            return;
        }

        synchronized (pendingPreparations) {
            List<PreparationCallback> waiting = pendingPreparations.get(pair);
            if (waiting != null) {
                waiting.add(callback);
                callback.onProgress("The selected translation pair is already being prepared…");
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(callback);
            pendingPreparations.put(pair, waiting);
        }

        notifyPairProgress(pair, "Preparing " + LanguageSupport.displayName(source) + " ↔ "
                + LanguageSupport.displayName(target) + " translation models…");
        Translator translator = getTranslator(sourceMl, targetMl);
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            finishPairPreparation(pair, false,
                    "Translation model preparation timed out. Listening and recording can still start, "
                            + "but translation needs an internet connection once to finish installing the selected pair.");
        };
        mainHandler.postDelayed(timeout, PAIR_MODEL_TIMEOUT_MS);

        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    finishPairPreparation(pair, true, "Translation models are ready.");
                })
                .addOnFailureListener(error -> {
                    if (!finished.compareAndSet(false, true)) return;
                    mainHandler.removeCallbacks(timeout);
                    finishPairPreparation(pair, false, modelFailureMessage(source, target, error));
                });
    }

    private void notifyPairProgress(String pair, String message) {
        List<PreparationCallback> callbacks;
        synchronized (pendingPreparations) {
            List<PreparationCallback> waiting = pendingPreparations.get(pair);
            callbacks = waiting == null ? Collections.emptyList() : new ArrayList<>(waiting);
        }
        for (PreparationCallback callback : callbacks) callback.onProgress(message);
    }

    private void finishPairPreparation(String pair, boolean success, String message) {
        List<PreparationCallback> callbacks;
        synchronized (pendingPreparations) {
            List<PreparationCallback> waiting = pendingPreparations.remove(pair);
            callbacks = waiting == null ? Collections.emptyList() : new ArrayList<>(waiting);
        }
        if (success) readyPairs.add(pair); else readyPairs.remove(pair);
        for (PreparationCallback callback : callbacks) {
            if (success) {
                callback.onProgress(message);
                callback.onComplete();
            } else {
                callback.onError(message);
            }
        }
    }

    private String pairKey(String sourceMl, String targetMl) {
        return (sourceMl == null ? "" : sourceMl) + ">" + (targetMl == null ? "" : targetMl);
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
                + " translation model could not be prepared. Check that this phone is online, "
                + "Google Play Services is enabled and there is enough free storage. " + readable(error);
    }

    private String readable(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Check the internet connection, Google Play Services and free storage, then retry.";
        }
        return message.trim();
    }
}
