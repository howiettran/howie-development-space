package au.com.transpired.howietranslate;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Phrase-by-phrase online recognition using an installed Android RecognitionService.
 *
 * Samsung devices can expose more than one service. The system default may report itself as
 * available but never deliver microphone callbacks for a requested language. This implementation
 * enumerates all declared RecognitionService components, prefers Google services, verifies
 * language support where Android 13+ provides that API, and automatically tries the next service
 * when a component becomes silent or rejects the selected language.
 */
final class GoogleOnlineSpeechManager {
    private static final long RESTART_AFTER_RESULT_MS = 60L;
    private static final long RESTART_AFTER_NO_MATCH_MS = 140L;
    private static final long RESTART_AFTER_BUSY_MS = 650L;
    private static final long SESSION_WATCHDOG_MS = 18000L;
    private static final long SILENT_SERVICE_WATCHDOG_MS = 4200L;
    private static final long STOP_WATCHDOG_MS = 2200L;
    private static final long END_OF_SPEECH_RESULT_WAIT_MS = 1400L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean stoppedDelivered;
    private volatile String requestedLanguage = "en";

    private SpeechRecognizer recognizer;
    private StreamingSpeechManager.Callback callback;
    private List<String> biasingStrings = new ArrayList<>();
    private final List<ServiceCandidate> serviceCandidates = new ArrayList<>();
    private int serviceIndex;
    private ComponentName preferredService;
    private ServiceCandidate activeCandidate;

    private long conversationStartedElapsed;
    private long phraseStartedMs;
    private long sessionGeneration;
    private boolean sessionActive;
    private boolean resultDeliveredForSession;
    private boolean beginningReceived;
    private int rmsEventCount;
    private float maxRms;
    private String sessionLanguage = "en";
    private String lastPartialText = "";
    private long lastPartialElapsedMs;

    private Runnable restartRunnable;
    private Runnable sessionWatchdog;
    private Runnable silentServiceWatchdog;
    private Runnable endOfSpeechFallback;
    private Runnable stopWatchdog;

    private static final class ServiceCandidate {
        final ComponentName component;
        final String label;
        final int priority;

        ServiceCandidate(ComponentName component, String label, int priority) {
            this.component = component;
            this.label = label;
            this.priority = priority;
        }
    }

    GoogleOnlineSpeechManager(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isSupported() {
        return SpeechRecognizer.isRecognitionAvailable(context) || !discoverServices().isEmpty();
    }

    boolean isRunning() {
        return running;
    }

    void startConversation(String sourceLanguage, String secondLanguage, File ignoredOutputFile,
                           List<String> speechBiases, StreamingSpeechManager.Callback callback) {
        mainHandler.post(() -> startOnMain(sourceLanguage, speechBiases, callback));
    }

    private void startOnMain(String sourceLanguage, List<String> speechBiases,
                             StreamingSpeechManager.Callback newCallback) {
        endWithoutCallback();
        this.callback = newCallback;
        this.requestedLanguage = normalise(sourceLanguage);
        this.biasingStrings = speechBiases == null ? new ArrayList<>() : new ArrayList<>(speechBiases);
        this.stopRequested = false;
        this.stoppedDelivered = false;
        this.running = true;
        this.conversationStartedElapsed = SystemClock.elapsedRealtime();
        this.sessionGeneration++;

        serviceCandidates.clear();
        serviceCandidates.addAll(discoverServices());
        serviceIndex = 0;

        if (serviceCandidates.isEmpty() && !SpeechRecognizer.isRecognitionAvailable(context)) {
            postError("No Android speech-recognition service is installed. Install or update Speech Recognition & Synthesis from Google, then try again.");
            finishConversation();
            return;
        }

        postPreparing("Selecting a compatible speech service for " + displayName(requestedLanguage) + "…");
        scheduleStart(100L);
    }

    void switchLanguage(String language) {
        mainHandler.post(() -> {
            requestedLanguage = normalise(language);
            if (!running || stopRequested) return;
            postPartial(requestedLanguage, "");
            postPreparing("Switching recognition to " + displayName(requestedLanguage) + "…");
            cancelCurrentSession();
            serviceIndex = 0;
            scheduleStart(220L);
        });
    }

    void stop() {
        mainHandler.post(this::stopOnMain);
    }

    private void stopOnMain() {
        if (!running && !sessionActive) {
            finishConversation();
            return;
        }
        stopRequested = true;
        cancelRestart();
        cancelWatchdogs();
        postPreparing("Stopping speech recognition and finalising the transcript…");

        if (recognizer != null && sessionActive) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
                cancelCurrentSession();
            }
        } else {
            finishConversation();
            return;
        }

        if (stopWatchdog != null) mainHandler.removeCallbacks(stopWatchdog);
        stopWatchdog = () -> {
            cancelCurrentSession();
            finishConversation();
        };
        mainHandler.postDelayed(stopWatchdog, STOP_WATCHDOG_MS);
    }

    void forceStop() {
        mainHandler.post(() -> {
            stopRequested = true;
            cancelRestart();
            cancelWatchdogs();
            cancelCurrentSession();
            finishConversation();
        });
    }

    void close() {
        mainHandler.post(() -> {
            stopRequested = true;
            cancelRestart();
            cancelWatchdogs();
            cancelCurrentSession();
            callback = null;
            running = false;
        });
    }

    private void scheduleStart(long delayMs) {
        cancelRestart();
        if (!running || stopRequested) {
            finishConversation();
            return;
        }
        final long generation = ++sessionGeneration;
        restartRunnable = () -> startListeningSession(generation);
        mainHandler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void startListeningSession(long generation) {
        if (generation != sessionGeneration || !running || stopRequested) {
            if (stopRequested) finishConversation();
            return;
        }

        sessionActive = false;
        cancelWatchdogs();
        destroyRecognizer(true);
        resultDeliveredForSession = false;
        beginningReceived = false;
        rmsEventCount = 0;
        maxRms = -100f;
        lastPartialText = "";
        lastPartialElapsedMs = 0L;
        sessionLanguage = normalise(requestedLanguage);
        phraseStartedMs = elapsedConversationMs();
        activeCandidate = candidateAt(serviceIndex);

        try {
            recognizer = activeCandidate != null && activeCandidate.component != null
                    ? SpeechRecognizer.createSpeechRecognizer(context, activeCandidate.component)
                    : SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(buildListener(generation));
            Intent intent = buildRecognizerIntent(sessionLanguage);
            // Start immediately. Some Samsung/Google service combinations take more than a
            // second to answer checkRecognitionSupport(), which made every phrase feel sluggish.
            // Unsupported languages are still handled through the normal RecognitionListener
            // error callbacks and service fallback logic below.
            startRecognizerNow(generation, intent);
        } catch (Exception e) {
            sessionActive = false;
            destroyRecognizer(false);
            if (stopRequested) finishConversation();
            else tryNextServiceOrRetry("could not start");
        }
    }

    private RecognitionListener buildListener(long generation) {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (!isCurrent(generation)) return;
                sessionActive = true;
                postPreparing("Listening with " + activeServiceName() + " • " + displayName(sessionLanguage));
            }

            @Override public void onBeginningOfSpeech() {
                if (!isCurrent(generation)) return;
                beginningReceived = true;
                preferredService = activeCandidate == null ? null : activeCandidate.component;
                phraseStartedMs = elapsedConversationMs();
                cancelSilentServiceWatchdog();
                postPreparing("Hearing " + displayName(sessionLanguage) + " • pause naturally to translate…");
            }

            @Override public void onRmsChanged(float rmsdB) {
                if (!isCurrent(generation)) return;
                rmsEventCount++;
                maxRms = Math.max(maxRms, rmsdB);
                int level = (int) Math.max(0, Math.min(100, (rmsdB + 2f) * 6f));
                postLevel(level);
                if (rmsEventCount > 3 && maxRms > 0.5f) {
                    preferredService = activeCandidate == null ? null : activeCandidate.component;
                }
            }

            @Override public void onBufferReceived(byte[] buffer) { }

            @Override public void onEndOfSpeech() {
                if (!isCurrent(generation)) return;
                postPreparing("Recognising and translating the phrase…");
                scheduleEndOfSpeechFallback(generation);
            }

            @Override public void onError(int error) {
                if (!isCurrent(generation)) return;
                sessionActive = false;
                cancelWatchdogs();
                destroyRecognizer(false);

                if (stopRequested) {
                    finishConversation();
                    return;
                }

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    postError("Microphone permission is missing.");
                    stopRequested = true;
                    finishConversation();
                    return;
                }

                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    if (advanceService()) {
                        postPreparing(activeServiceName() + " did not support " + displayName(sessionLanguage) + ". Trying another installed speech service…");
                        scheduleStart(300L);
                    } else {
                        postError("None of the installed speech services supports " + displayName(sessionLanguage) + ". Update Speech Recognition & Synthesis from Google and confirm the language is enabled in Android settings.");
                        stopRequested = true;
                        finishConversation();
                    }
                    return;
                }

                if ((error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_AUDIO)
                        && !beginningReceived && rmsEventCount == 0 && advanceService()) {
                    postPreparing("No microphone response from the previous service. Trying " + activeServiceName() + "…");
                    scheduleStart(300L);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error == SpeechRecognizer.ERROR_CLIENT) {
                    if (deliverLastPartialIfUseful()) {
                        postPartial(sessionLanguage, "");
                        scheduleStart(RESTART_AFTER_RESULT_MS);
                        return;
                    }
                    postPartial(sessionLanguage, "");
                    scheduleStart(RESTART_AFTER_NO_MATCH_MS);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)) {
                    postPreparing(activeServiceName() + " is briefly busy. Retrying…");
                    scheduleStart(RESTART_AFTER_BUSY_MS);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        || error == SpeechRecognizer.ERROR_SERVER
                        || (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) {
                    postPreparing("Online speech connection was interrupted. Retrying…");
                    scheduleStart(1000L);
                    return;
                }

                postPreparing("Speech recognition: " + errorMessage(error) + ". Retrying…");
                scheduleStart(650L);
            }

            @Override public void onResults(Bundle results) {
                if (!isCurrent(generation)) return;
                sessionActive = false;
                cancelWatchdogs();
                String text = cleanTranscript(bestText(results), sessionLanguage);
                long endMs = Math.max(phraseStartedMs + 200L, elapsedConversationMs());
                if (!text.isEmpty() && !resultDeliveredForSession) {
                    resultDeliveredForSession = true;
                    preferredService = activeCandidate == null ? null : activeCandidate.component;
                    postSegment(sessionLanguage, text, phraseStartedMs, endMs);
                }
                postPartial(sessionLanguage, "");
                destroyRecognizer(false);
                if (stopRequested) finishConversation();
                else scheduleStart(RESTART_AFTER_RESULT_MS);
            }

            @Override public void onPartialResults(Bundle partialResults) {
                if (!isCurrent(generation)) return;
                String partial = cleanTranscript(bestText(partialResults), sessionLanguage);
                if (!partial.isEmpty()) {
                    lastPartialText = partial;
                    lastPartialElapsedMs = elapsedConversationMs();
                    preferredService = activeCandidate == null ? null : activeCandidate.component;
                    postPartial(sessionLanguage, partial);
                }
            }

            @Override public void onEvent(int eventType, Bundle params) { }
        };
    }

    private void startRecognizerNow(long generation, Intent intent) {
        if (!isCurrent(generation) || recognizer == null) return;
        try {
            sessionActive = true;
            recognizer.startListening(intent);
            scheduleSessionWatchdogs(generation);
        } catch (Exception e) {
            sessionActive = false;
            destroyRecognizer(false);
            tryNextServiceOrRetry("failed to open the microphone");
        }
    }

    private void scheduleSessionWatchdogs(long generation) {
        cancelSessionWatchdog();
        sessionWatchdog = () -> {
            if (!isCurrent(generation) || stopRequested) return;
            if (!beginningReceived && rmsEventCount == 0 && advanceService()) {
                postPreparing("The previous speech service stayed silent. Trying " + activeServiceName() + "…");
                cancelCurrentSession();
                scheduleStart(250L);
                return;
            }
            postPreparing("Refreshing " + activeServiceName() + "…");
            try {
                if (recognizer != null) recognizer.stopListening();
            } catch (Exception ignored) {
                cancelCurrentSession();
                scheduleStart(300L);
            }
        };
        mainHandler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS);

        cancelSilentServiceWatchdog();
        silentServiceWatchdog = () -> {
            if (!isCurrent(generation) || stopRequested || beginningReceived) return;
            if (rmsEventCount == 0 && advanceService()) {
                postPreparing("No microphone activity from the previous service. Trying " + activeServiceName() + "…");
                cancelCurrentSession();
                scheduleStart(250L);
            }
        };
        mainHandler.postDelayed(silentServiceWatchdog, SILENT_SERVICE_WATCHDOG_MS);
    }

    private void scheduleEndOfSpeechFallback(long generation) {
        cancelEndOfSpeechFallback();
        endOfSpeechFallback = () -> {
            if (!isCurrent(generation) || stopRequested || resultDeliveredForSession) return;
            if (deliverLastPartialIfUseful()) {
                resultDeliveredForSession = true;
                postPartial(sessionLanguage, "");
                cancelCurrentSession();
                scheduleStart(RESTART_AFTER_RESULT_MS);
            }
        };
        mainHandler.postDelayed(endOfSpeechFallback, END_OF_SPEECH_RESULT_WAIT_MS);
    }

    private boolean deliverLastPartialIfUseful() {
        String text = cleanTranscript(lastPartialText, sessionLanguage);
        if (text.isEmpty()) return false;
        long endMs = Math.max(phraseStartedMs + 200L,
                lastPartialElapsedMs > 0L ? lastPartialElapsedMs : elapsedConversationMs());
        postSegment(sessionLanguage, text, phraseStartedMs, endMs);
        lastPartialText = "";
        lastPartialElapsedMs = 0L;
        return true;
    }

    private Intent buildRecognizerIntent(String language) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag(language));
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag(language));
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak " + displayName(language));
        // These are hints only; recognizer implementations may ignore them.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 450L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 560L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 360L);
        if (Build.VERSION.SDK_INT >= 33 && !biasingStrings.isEmpty()) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    new ArrayList<>(biasingStrings.subList(0, Math.min(50, biasingStrings.size()))));
        }
        return intent;
    }

    private List<ServiceCandidate> discoverServices() {
        List<ServiceCandidate> found = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(RecognitionService.SERVICE_INTERFACE);
        List<ResolveInfo> infos;
        try {
            infos = pm.queryIntentServices(query, PackageManager.MATCH_ALL);
        } catch (Exception e) {
            infos = Collections.emptyList();
        }
        for (ResolveInfo info : infos) {
            ServiceInfo service = info.serviceInfo;
            if (service == null || !service.enabled) continue;
            ComponentName component = new ComponentName(service.packageName, service.name);
            String label;
            try {
                CharSequence loaded = info.loadLabel(pm);
                label = loaded == null ? service.packageName : loaded.toString();
            } catch (Exception ignored) {
                label = service.packageName;
            }
            int priority = servicePriority(service.packageName, label);
            if (preferredService != null && preferredService.equals(component)) priority = -100;
            found.add(new ServiceCandidate(component, label + " (" + service.packageName + ")", priority));
        }
        found.sort(Comparator.comparingInt((ServiceCandidate c) -> c.priority)
                .thenComparing(c -> c.label.toLowerCase(Locale.ROOT)));
        // Keep the system default as a final fallback if it was not already represented.
        found.add(new ServiceCandidate(null, "Android system default", 1000));
        return found;
    }

    private static int servicePriority(String packageName, String label) {
        String value = (safe(packageName) + " " + safe(label)).toLowerCase(Locale.ROOT);
        if (value.contains("com.google.android.googlequicksearchbox")) return 0;
        if (value.contains("com.google.android.tts")) return 1;
        if (value.contains("google")) return 2;
        if (value.contains("samsung")) return 20;
        return 50;
    }

    private ServiceCandidate candidateAt(int index) {
        if (serviceCandidates.isEmpty()) return null;
        int safeIndex = Math.max(0, Math.min(index, serviceCandidates.size() - 1));
        return serviceCandidates.get(safeIndex);
    }

    private boolean advanceService() {
        if (serviceIndex + 1 >= serviceCandidates.size()) return false;
        serviceIndex++;
        activeCandidate = candidateAt(serviceIndex);
        return true;
    }

    private void tryNextServiceOrRetry(String reason) {
        if (advanceService()) {
            postPreparing("The previous service " + reason + ". Trying " + activeServiceName() + "…");
            scheduleStart(350L);
        } else {
            postPreparing("The speech service " + reason + ". Retrying…");
            serviceIndex = 0;
            scheduleStart(900L);
        }
    }

    private String activeServiceName() {
        return activeCandidate == null ? "Android speech service" : activeCandidate.label;
    }

    private static boolean languageListContains(List<String> values, String requestedTag) {
        if (values == null || values.isEmpty()) return false;
        String requested = requestedTag.toLowerCase(Locale.ROOT);
        String base = requested.split("-")[0];
        for (String value : values) {
            if (value == null) continue;
            String item = value.toLowerCase(Locale.ROOT);
            if (item.equals(requested) || item.equals(base) || item.startsWith(base + "-")) return true;
        }
        return false;
    }

    private boolean isCurrent(long generation) {
        return generation == sessionGeneration && running && !stoppedDelivered;
    }

    private long elapsedConversationMs() {
        return conversationStartedElapsed == 0L ? 0L
                : Math.max(0L, SystemClock.elapsedRealtime() - conversationStartedElapsed);
    }

    private void cancelRestart() {
        if (restartRunnable != null) {
            mainHandler.removeCallbacks(restartRunnable);
            restartRunnable = null;
        }
    }

    private void cancelWatchdogs() {
        cancelSessionWatchdog();
        cancelSilentServiceWatchdog();
        cancelEndOfSpeechFallback();
    }

    private void cancelSessionWatchdog() {
        if (sessionWatchdog != null) {
            mainHandler.removeCallbacks(sessionWatchdog);
            sessionWatchdog = null;
        }
    }

    private void cancelSilentServiceWatchdog() {
        if (silentServiceWatchdog != null) {
            mainHandler.removeCallbacks(silentServiceWatchdog);
            silentServiceWatchdog = null;
        }
    }

    private void cancelEndOfSpeechFallback() {
        if (endOfSpeechFallback != null) {
            mainHandler.removeCallbacks(endOfSpeechFallback);
            endOfSpeechFallback = null;
        }
    }

    private void cancelCurrentSession() {
        sessionGeneration++;
        sessionActive = false;
        cancelWatchdogs();
        destroyRecognizer(true);
    }

    private void destroyRecognizer(boolean cancel) {
        if (recognizer != null) {
            try { if (cancel) recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void endWithoutCallback() {
        cancelRestart();
        cancelWatchdogs();
        if (stopWatchdog != null) {
            mainHandler.removeCallbacks(stopWatchdog);
            stopWatchdog = null;
        }
        destroyRecognizer(true);
        sessionActive = false;
        running = false;
        stopRequested = false;
        stoppedDelivered = false;
    }

    private void finishConversation() {
        if (stoppedDelivered) return;
        if (!resultDeliveredForSession) {
            resultDeliveredForSession = deliverLastPartialIfUseful();
        }
        cancelRestart();
        cancelWatchdogs();
        if (stopWatchdog != null) {
            mainHandler.removeCallbacks(stopWatchdog);
            stopWatchdog = null;
        }
        destroyRecognizer(true);
        sessionActive = false;
        running = false;
        stoppedDelivered = true;
        long duration = elapsedConversationMs();
        postLevel(0);
        postPartial(requestedLanguage, "");
        postStopped(null, duration);
    }

    private String bestText(Bundle results) {
        if (results == null) return "";
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null || values.isEmpty()) return "";
        float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        if (confidence == null || confidence.length != values.size()) return safe(values.get(0));
        int best = 0;
        float bestScore = confidence[0];
        for (int i = 1; i < confidence.length; i++) {
            if (confidence[i] > bestScore) {
                bestScore = confidence[i];
                best = i;
            }
        }
        return safe(values.get(best));
    }

    private static String cleanTranscript(String input, String language) {
        String text = safe(input).replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        if (containsUnexpectedScript(text, language)) return "";
        if (LanguageSupport.isChineseScript(language)) {
            text = text.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        }
        return text;
    }

    private static boolean containsUnexpectedScript(String text, String language) {
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (!LanguageSupport.acceptsScript(language, script)) return true;
        }
        return false;
    }

    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio input failed";
            case SpeechRecognizer.ERROR_CLIENT: return "the speech service ended the listening session";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "microphone permission is missing";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "the selected language is not supported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "the selected language is temporarily unavailable";
            case SpeechRecognizer.ERROR_NETWORK: return "network connection failed";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network connection timed out";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no speech match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "the speech recognizer is busy";
            case SpeechRecognizer.ERROR_SERVER: return "the speech service returned an error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech was heard";
            default:
                if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                    return "too many recognition requests";
                }
                if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                    return "the speech service disconnected";
                }
                return "code " + error;
        }
    }

    private static String normalise(String language) {
        return LanguageSupport.normalise(language);
    }

    private static String localeTag(String language) {
        return LanguageSupport.localeTag(language);
    }

    private static String displayName(String language) {
        return LanguageSupport.displayName(language);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void postPreparing(String message) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onPreparing(message));
    }

    private void postLevel(int percent) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onAudioLevel(percent));
    }

    private void postPartial(String language, String text) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onPartial(language, text));
    }

    private void postSegment(String language, String text, long startMs, long endMs) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onSegment(language, text, startMs, endMs));
    }

    private void postError(String message) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onError(message));
    }

    private void postStopped(File file, long duration) {
        StreamingSpeechManager.Callback cb = callback;
        if (cb != null) mainHandler.post(() -> cb.onStopped(file, duration));
    }
}
