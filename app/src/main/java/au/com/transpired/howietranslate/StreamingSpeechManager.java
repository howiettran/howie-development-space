package au.com.transpired.howietranslate;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import dev.ffmpegkit.whisper.Whisper;
import dev.ffmpegkit.whisper.WhisperConfig;
import dev.ffmpegkit.whisper.WhisperModel;
import dev.ffmpegkit.whisper.WhisperResult;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import android.content.res.AssetFileDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Continuous on-device Whisper microphone pipeline.
 *
 * AudioRecord stays open for the whole conversation. A lightweight adaptive voice detector cuts
 * speech at natural pauses, then the bundled multilingual Whisper model transcribes each short
 * phrase on a separate worker while microphone capture continues. This avoids the old behaviour
 * of collecting a long paragraph before returning a translation.
 */
final class StreamingSpeechManager {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SAMPLES = 320;             // 20 ms at 16 kHz
    private static final int PRE_ROLL_FRAMES = 25;            // 500 ms
    private static final int START_VOICE_FRAMES = 3;          // 60 ms
    private static final int END_SILENCE_FRAMES = 40;         // 800 ms
    private static final int MIN_PHRASE_FRAMES = 25;          // 500 ms
    private static final int MAX_PHRASE_FRAMES = 400;         // 8.0 sec
    private static final int ONE_SHOT_TIMEOUT_MS = 30000;
    private static final String MODEL_ASSET = OfflineSpeechManager.WHISPER_ASSET_PATH;

    interface Callback {
        void onPreparing(String message);
        void onAudioLevel(int percent);
        void onPartial(String language, String text);
        void onSegment(String language, String text, long startMs, long endMs);
        void onError(String message);
        void onStopped(File audioFile, long durationMs);
    }

    private interface SuspendCall<T> {
        Object invoke(Continuation<? super T> continuation);
    }

    private static final class Phrase {
        final File file;
        final String hintedLanguage;
        final String otherLanguage;
        final long startMs;
        final long endMs;
        final boolean oneShot;

        Phrase(File file, String hintedLanguage, String otherLanguage, long startMs, long endMs, boolean oneShot) {
            this.file = file;
            this.hintedLanguage = hintedLanguage;
            this.otherLanguage = otherLanguage;
            this.startMs = startMs;
            this.endMs = endMs;
            this.oneShot = oneShot;
        }
    }

    private final Context context;
    private final OfflineSpeechManager modelManager;
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object recorderLock = new Object();
    private final Object modelLock = new Object();
    private final Object pendingLock = new Object();

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean oneShotDelivered;
    private volatile String requestedLanguage;
    private volatile Thread captureThread;
    private volatile int pendingTranscriptions;
    private AudioRecord audioRecord;
    private WhisperModel whisperModel;
    private final LanguageIdentifier languageIdentifier = LanguageIdentification.getClient(
            new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.10f).build());

    StreamingSpeechManager(Context context, OfflineSpeechManager modelManager) {
        this.context = context.getApplicationContext();
        this.modelManager = modelManager;
    }

    boolean isRunning() {
        return running;
    }

    void startOneShot(String language, Callback callback) {
        start(language, null, null, true, callback);
    }

    void startConversation(String sourceLanguage, String secondLanguage, File outputFile, Callback callback) {
        start(sourceLanguage, secondLanguage, outputFile, false, callback);
    }

    private void start(String sourceLanguage, String secondLanguage, File outputFile,
                       boolean oneShot, Callback callback) {
        stop();
        waitBrieflyForPreviousSession();
        String primary = normalise(sourceLanguage);
        String secondary = secondLanguage == null ? null : normalise(secondLanguage);
        if (!modelManager.isInstalled(primary)) {
            postError(callback, "The bundled Whisper speech model is missing. Reinstall the app.");
            return;
        }
        stopRequested = false;
        oneShotDelivered = false;
        requestedLanguage = primary;
        sessionExecutor.execute(() -> runSession(primary, secondary, outputFile, oneShot, callback));
    }

    void switchLanguage(String language) {
        requestedLanguage = normalise(language);
        // Empty partial clears the prior speaker's temporary caption immediately.
    }

    void stop() {
        stopRequested = true;
        AudioRecord current;
        synchronized (recorderLock) {
            current = audioRecord;
        }
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) { }
        }
        Thread thread = captureThread;
        if (thread != null) thread.interrupt();
    }

    void close() {
        stop();
        sessionExecutor.shutdownNow();
        transcriptionExecutor.shutdown();
        try { languageIdentifier.close(); } catch (Exception ignored) { }
        synchronized (modelLock) {
            if (whisperModel != null) {
                try { Whisper.INSTANCE.releaseModel(whisperModel); } catch (Exception ignored) { }
                whisperModel = null;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void runSession(String primary, String secondary, File outputFile, boolean oneShot, Callback callback) {
        BufferedOutputStream audioOut = null;
        File completedAudio = null;
        long pcmBytes = 0;
        long startedAt = 0;
        ByteArrayOutputStream phrasePcm = null;
        Deque<short[]> preRoll = new ArrayDeque<>();
        boolean inSpeech = false;
        int voiceRun = 0;
        int silenceRun = 0;
        int phraseFrames = 0;
        int voicedPhraseFrames = 0;
        long phraseStartMs = 0;
        String phraseLanguage = primary;
        double noiseFloor = 0.0065;

        try {
            postPreparing(callback, "Loading the built-in Whisper model…");
            ensureModelLoaded();
            if (stopRequested) return;

            AudioRecord recorder = createAudioRecord();
            synchronized (recorderLock) {
                audioRecord = recorder;
            }

            if (outputFile != null) {
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("The recording folder could not be created.");
                }
                audioOut = new BufferedOutputStream(new FileOutputStream(outputFile));
                writeWavHeader(audioOut, 0);
                completedAudio = outputFile;
            }

            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("The microphone did not start recording.");
            }

            running = true;
            captureThread = Thread.currentThread();
            startedAt = SystemClock.elapsedRealtime();
            if (secondary == null || secondary.equals(primary)) {
                postPreparing(callback, "Whisper ready • listening for "
                        + OfflineSpeechManager.modelInfo(primary).displayName + "…");
            } else {
                postPreparing(callback, "Whisper ready • language locked to "
                        + OfflineSpeechManager.modelInfo(primary).displayName
                        + " • tap ⇄ when the speaker changes…");
            }

            short[] frame = new short[FRAME_SAMPLES];
            long lastLevelAt = 0;

            while (!stopRequested && !Thread.currentThread().isInterrupted()) {
                int read = readFrame(recorder, frame);
                if (read <= 0) continue;
                long elapsed = SystemClock.elapsedRealtime() - startedAt;

                if (audioOut != null) {
                    writePcm16(audioOut, frame, read);
                    pcmBytes += (long) read * 2L;
                }

                double rms = rms(frame, read);
                double peak = peak(frame, read);
                if (elapsed - lastLevelAt >= 100) {
                    postLevel(callback, audioLevel(rms));
                    lastLevelAt = elapsed;
                }

                if (!inSpeech) {
                    noiseFloor = Math.max(0.0025, Math.min(0.025,
                            noiseFloor * 0.975 + Math.min(rms, 0.035) * 0.025));
                }
                double threshold = Math.max(0.0065, noiseFloor * 1.85);
                boolean voiced = rms >= threshold || peak >= Math.max(0.035, threshold * 3.2);

                if (!inSpeech) {
                    addPreRoll(preRoll, frame, read);
                    voiceRun = voiced ? voiceRun + 1 : 0;
                    if (voiceRun >= START_VOICE_FRAMES) {
                        inSpeech = true;
                        silenceRun = 0;
                        phraseFrames = preRoll.size();
                        voicedPhraseFrames = voiceRun;
                        phrasePcm = new ByteArrayOutputStream((MAX_PHRASE_FRAMES + PRE_ROLL_FRAMES)
                                * FRAME_SAMPLES * 2);
                        phraseStartMs = Math.max(0, elapsed - (long) preRoll.size() * 20L);
                        phraseLanguage = normalise(requestedLanguage);
                        for (short[] saved : preRoll) writePcm16(phrasePcm, saved, saved.length);
                        preRoll.clear();
                        postPreparing(callback, "Hearing "
                                + OfflineSpeechManager.modelInfo(phraseLanguage).displayName
                                + " • pause briefly to translate…");
                    }
                } else {
                    writePcm16(phrasePcm, frame, read);
                    phraseFrames++;
                    if (voiced) {
                        silenceRun = 0;
                        voicedPhraseFrames++;
                    } else {
                        silenceRun++;
                    }

                    boolean naturalPause = silenceRun >= END_SILENCE_FRAMES;
                    boolean maximumLength = phraseFrames >= MAX_PHRASE_FRAMES;
                    if (naturalPause || maximumLength) {
                        long trimSilenceMs = naturalPause ? Math.min(420L, silenceRun * 20L) : 0L;
                        long phraseEndMs = Math.max(phraseStartMs + 200L, elapsed - trimSilenceMs);
                        if (voicedPhraseFrames >= MIN_PHRASE_FRAMES / 3
                                && phraseFrames >= MIN_PHRASE_FRAMES) {
                            File chunk = writeChunkFile(phrasePcm.toByteArray());
                            queuePhrase(new Phrase(chunk, phraseLanguage, secondary, phraseStartMs,
                                    phraseEndMs, oneShot), callback);
                        }
                        phrasePcm = null;
                        inSpeech = false;
                        voiceRun = 0;
                        silenceRun = 0;
                        phraseFrames = 0;
                        voicedPhraseFrames = 0;
                        preRoll.clear();
                        postPartial(callback, phraseLanguage, "");
                        if (!oneShot) {
                            postPreparing(callback, "Listening continuously…");
                        }
                    }
                }

                if (oneShot && (oneShotDelivered || elapsed >= ONE_SHOT_TIMEOUT_MS)) break;
            }

            if (inSpeech && phrasePcm != null && phraseFrames >= MIN_PHRASE_FRAMES) {
                long elapsed = startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt;
                File chunk = writeChunkFile(phrasePcm.toByteArray());
                queuePhrase(new Phrase(chunk, phraseLanguage, secondary, phraseStartMs, elapsed, oneShot), callback);
            }
        } catch (Exception e) {
            if (!stopRequested) postError(callback, "Whisper speech recognition stopped. " + safeMessage(e));
        } finally {
            running = false;
            captureThread = null;

            AudioRecord recorder;
            synchronized (recorderLock) {
                recorder = audioRecord;
                audioRecord = null;
            }
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) { }
                try { recorder.release(); } catch (Exception ignored) { }
            }
            if (audioOut != null) {
                try { audioOut.flush(); } catch (Exception ignored) { }
                try { audioOut.close(); } catch (Exception ignored) { }
                if (completedAudio != null) {
                    try { patchWavHeader(completedAudio, pcmBytes); } catch (Exception ignored) { }
                }
            }

            waitForPendingTranscriptions(90000);
            long duration = startedAt == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - startedAt);
            File finalAudio = completedAudio;
            mainHandler.post(() -> callback.onStopped(finalAudio, duration));
        }
    }

    private void queuePhrase(Phrase phrase, Callback callback) {
        synchronized (pendingLock) {
            pendingTranscriptions++;
        }
        transcriptionExecutor.execute(() -> {
            try {
                if (phrase.oneShot && oneShotDelivered) return;
                postPreparing(callback, "Whisper is transcribing the latest phrase…");
                // Accuracy fix: Whisper Tiny is far more reliable when the selected speaker
                // language is passed explicitly. "auto" was allowing unrelated scripts and
                // languages to leak into Vietnamese and English transcripts.
                String whisperLanguage = phrase.hintedLanguage;
                WhisperConfig config = new WhisperConfig(
                        whisperLanguage,
                        false,
                        recommendedThreadCount(),
                        128,
                        true
                );
                WhisperModel model = requireModel();
                WhisperResult result = awaitSuspend(continuation ->
                        Whisper.INSTANCE.transcribe(model, phrase.file.getAbsolutePath(), config, continuation),
                        120000);
                String rawText = result == null || result.getText() == null ? "" : result.getText().trim();
                String initialText = cleanTranscript(rawText, phrase.hintedLanguage);
                if (!initialText.isEmpty() && !(phrase.oneShot && oneShotDelivered)) {
                    // Keep the transcript locked to the language selected by the user. The
                    // conversation swap control calls switchLanguage() when the other person speaks.
                    String resolvedLanguage = phrase.hintedLanguage;
                    String text = cleanTranscript(rawText, resolvedLanguage);
                    if (text.isEmpty()) return;
                    if (phrase.oneShot) oneShotDelivered = true;
                    postSegment(callback, resolvedLanguage, text, phrase.startMs, phrase.endMs);
                }
            } catch (Exception e) {
                if (!stopRequested || !isCancellationLike(e)) {
                    postError(callback, "This phrase could not be transcribed. " + safeMessage(e));
                }
            } finally {
                if (phrase.file != null) phrase.file.delete();
                synchronized (pendingLock) {
                    pendingTranscriptions = Math.max(0, pendingTranscriptions - 1);
                    pendingLock.notifyAll();
                }
            }
        });
    }

    private WhisperModel requireModel() throws IOException {
        synchronized (modelLock) {
            if (whisperModel == null || !whisperModel.isValid()) {
                throw new IOException("The Whisper model is not loaded.");
            }
            return whisperModel;
        }
    }

    private void ensureModelLoaded() throws Exception {
        validateCachedModelCopy();
        synchronized (modelLock) {
            if (whisperModel != null && whisperModel.isValid()) return;
        }
        WhisperModel loaded = awaitSuspend(continuation ->
                Whisper.INSTANCE.loadModelFromAsset(context, MODEL_ASSET, continuation), 180000);
        if (loaded == null || !loaded.isValid()) throw new IOException("Whisper could not load its model.");
        synchronized (modelLock) {
            if (whisperModel != null && whisperModel.isValid()) {
                Whisper.INSTANCE.releaseModel(loaded);
            } else {
                whisperModel = loaded;
            }
        }
    }

    private <T> T awaitSuspend(SuspendCall<T> call, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Continuation<T> continuation = new Continuation<T>() {
            @Override public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @SuppressWarnings("unchecked")
            @Override public void resumeWith(Object result) {
                try {
                    ResultKt.throwOnFailure(result);
                    value.set((T) result);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    latch.countDown();
                }
            }
        };

        Object direct;
        try {
            direct = call.invoke(continuation);
        } catch (Throwable t) {
            throw asException(t);
        }
        if (direct != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            try {
                ResultKt.throwOnFailure(direct);
                @SuppressWarnings("unchecked") T immediate = (T) direct;
                return immediate;
            } catch (Throwable t) {
                throw asException(t);
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("Whisper took too long to process the audio.");
        }
        if (failure.get() != null) throw asException(failure.get());
        return value.get();
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception) return (Exception) t;
        return new Exception(t == null ? "Unknown error" : t.getMessage(), t);
    }

    private void validateCachedModelCopy() {
        File cached = new File(context.getCacheDir(), "ggml-tiny.bin");
        if (!cached.exists()) return;
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(MODEL_ASSET)) {
            long expected = descriptor.getLength();
            if (expected > 0 && cached.length() != expected) cached.delete();
        } catch (Exception ignored) {
            // The library will report a clear load error if the cached model is genuinely invalid.
        }
    }

    private String resolveLanguage(String text, String hintedLanguage, String otherLanguage) {
        if (otherLanguage == null || otherLanguage.equals(hintedLanguage)) return hintedLanguage;
        if (containsHan(text)) {
            if ("zh".equals(hintedLanguage) || "zh".equals(otherLanguage)) return "zh";
        }
        try {
            List<IdentifiedLanguage> candidates = Tasks.await(
                    languageIdentifier.identifyPossibleLanguages(text), 8, TimeUnit.SECONDS);
            String best = null;
            float confidence = 0f;
            for (IdentifiedLanguage candidate : candidates) {
                String code = normaliseIdentifiedLanguage(candidate.getLanguageTag());
                if ((code.equals(hintedLanguage) || code.equals(otherLanguage))
                        && candidate.getConfidence() > confidence) {
                    best = code;
                    confidence = candidate.getConfidence();
                }
            }
            // Short phrases can be ambiguous. Keep the user's current speaker hint unless the
            // detector has enough evidence to switch automatically.
            if (best != null && (best.equals(hintedLanguage) || confidence >= 0.22f)) return best;
        } catch (Exception ignored) { }
        return hintedLanguage;
    }

    private static String normaliseIdentifiedLanguage(String tag) {
        if (tag == null) return "";
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) return "zh";
        if (lower.startsWith("vi")) return "vi";
        if (lower.startsWith("en")) return "en";
        return lower;
    }

    private static boolean containsHan(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createAudioRecord() throws IOException {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (min <= 0) min = SAMPLE_RATE;
        int bufferBytes = Math.max(min * 4, SAMPLE_RATE * 2);

        List<Integer> sources = new ArrayList<>();
        // MIC first: some Samsung builds expose VOICE_RECOGNITION but feed it silence.
        sources.add(MediaRecorder.AudioSource.MIC);
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        for (int source : sources) {
            AudioRecord recorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG,
                    AUDIO_FORMAT, bufferBytes);
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) return recorder;
            recorder.release();
        }
        throw new IOException("The microphone could not be initialised. Close other recording apps and try again.");
    }

    private static int readFrame(AudioRecord recorder, short[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = recorder.read(target, offset, target.length - offset, AudioRecord.READ_BLOCKING);
            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE
                    || read == AudioRecord.ERROR_DEAD_OBJECT) {
                throw new IOException("The phone returned a microphone read error (" + read + ").");
            }
            if (read <= 0) return read;
            offset += read;
        }
        return offset;
    }

    private File writeChunkFile(byte[] pcm) throws IOException {
        File directory = new File(context.getCacheDir(), "whisper-phrases");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("The temporary speech folder could not be created.");
        }
        File file = File.createTempFile("phrase_", ".wav", directory);
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            writeWavHeader(out, pcm.length);
            out.write(pcm);
        }
        return file;
    }

    private static void addPreRoll(Deque<short[]> preRoll, short[] frame, int count) {
        short[] copy = new short[count];
        System.arraycopy(frame, 0, copy, 0, count);
        preRoll.addLast(copy);
        while (preRoll.size() > PRE_ROLL_FRAMES) preRoll.removeFirst();
    }

    private static double rms(short[] samples, int count) {
        if (count <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double sample = samples[i] / 32768.0;
            sum += sample * sample;
        }
        return Math.sqrt(sum / count);
    }

    private static double peak(short[] samples, int count) {
        double peak = 0;
        for (int i = 0; i < count; i++) {
            peak = Math.max(peak, Math.abs(samples[i] / 32768.0));
        }
        return peak;
    }

    private static int audioLevel(double rms) {
        return (int) Math.max(0, Math.min(100, Math.round(Math.sqrt(rms) * 220.0)));
    }

    private static int recommendedThreadCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(6, processors > 2 ? processors - 1 : processors));
    }

    private static String cleanTranscript(String input, String language) {
        if (input == null) return "";
        String text = input.trim()
                .replaceAll("(?i)\\[(blank_audio|silence|music|applause|inaudible)]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.equals(".") || text.equals("...") || text.length() == 1 && !Character.isLetterOrDigit(text.charAt(0))) {
            return "";
        }
        if (containsUnexpectedScript(text, language)) {
            // Do not show or translate a phrase when Tiny has hallucinated an unrelated writing
            // system. This protects Vietnamese/English sessions from Chinese, Korean, Japanese
            // or Cyrillic output and protects Chinese sessions from unrelated Asian scripts.
            return "";
        }
        if ("zh".equals(language)) {
            // Whisper sometimes inserts spaces between every Han character. Preserve spaces around Latin words.
            text = text.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        }
        return text;
    }

    private static boolean containsUnexpectedScript(String text, String language) {
        if (text == null || text.isEmpty()) return false;
        boolean chinese = "zh".equals(language);
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.COMMON
                    || script == Character.UnicodeScript.INHERITED
                    || script == Character.UnicodeScript.LATIN) {
                continue;
            }
            if (chinese && script == Character.UnicodeScript.HAN) continue;
            return true;
        }
        return false;
    }

    private void waitForPendingTranscriptions(long maximumMs) {
        long deadline = SystemClock.elapsedRealtime() + maximumMs;
        synchronized (pendingLock) {
            while (pendingTranscriptions > 0) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { pendingLock.wait(Math.min(remaining, 500)); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void waitBrieflyForPreviousSession() {
        long deadline = SystemClock.elapsedRealtime() + 1200;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            try { Thread.sleep(30); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static boolean isCancellationLike(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("interrupted") || message.contains("cancel");
    }

    private static void writeWavHeader(BufferedOutputStream out, long pcmBytes) throws IOException {
        long fileSize = pcmBytes + 36;
        long byteRate = SAMPLE_RATE * 2L;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeLittleEndian(header, 4, fileSize, 4);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeLittleEndian(header, 16, 16, 4);
        writeLittleEndian(header, 20, 1, 2);
        writeLittleEndian(header, 22, 1, 2);
        writeLittleEndian(header, 24, SAMPLE_RATE, 4);
        writeLittleEndian(header, 28, byteRate, 4);
        writeLittleEndian(header, 32, 2, 2);
        writeLittleEndian(header, 34, 16, 2);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeLittleEndian(header, 40, pcmBytes, 4);
        out.write(header);
    }

    private static void patchWavHeader(File file, long pcmBytes) throws IOException {
        try (RandomAccessFile random = new RandomAccessFile(file, "rw")) {
            random.seek(4);
            writeLittleEndian(random, pcmBytes + 36, 4);
            random.seek(40);
            writeLittleEndian(random, pcmBytes, 4);
        }
    }

    private static void writePcm16(BufferedOutputStream out, short[] data, int count) throws IOException {
        byte[] bytes = new byte[count * 2];
        for (int i = 0, j = 0; i < count; i++) {
            short value = data[i];
            bytes[j++] = (byte) (value & 0xff);
            bytes[j++] = (byte) ((value >>> 8) & 0xff);
        }
        out.write(bytes);
    }

    private static void writePcm16(ByteArrayOutputStream out, short[] data, int count) {
        for (int i = 0; i < count; i++) {
            short value = data[i];
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
        }
    }

    private static void writeLittleEndian(byte[] target, int offset, long value, int count) {
        for (int i = 0; i < count; i++) target[offset + i] = (byte) ((value >>> (8 * i)) & 0xff);
    }

    private static void writeLittleEndian(RandomAccessFile target, long value, int count) throws IOException {
        for (int i = 0; i < count; i++) target.write((int) ((value >>> (8 * i)) & 0xff));
    }

    private void postPreparing(Callback callback, String message) {
        mainHandler.post(() -> callback.onPreparing(message));
    }

    private void postLevel(Callback callback, int percent) {
        mainHandler.post(() -> callback.onAudioLevel(percent));
    }

    private void postPartial(Callback callback, String language, String text) {
        mainHandler.post(() -> callback.onPartial(language, text));
    }

    private void postSegment(Callback callback, String language, String text, long startMs, long endMs) {
        mainHandler.post(() -> callback.onSegment(language, text, startMs, endMs));
    }

    private void postError(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    private static String normalise(String language) {
        if ("zh".equals(language) || "vi".equals(language)) return language;
        return "en";
    }

    private static String safeMessage(Throwable e) {
        String message = e == null ? "" : e.getMessage();
        return message == null || message.trim().isEmpty() ? "Please try again." : message;
    }
}
