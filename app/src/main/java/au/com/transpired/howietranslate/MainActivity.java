package au.com.transpired.howietranslate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public class MainActivity extends Activity {
    private static final String HISTORY_ONLY_MARKER = AppDatabase.HISTORY_ONLY_MARKER;
    private static final int REQUEST_RECORD_AUDIO = 910;
    private static final int REQUEST_SPEECH = 911;
    private static final int REQUEST_OCR_CAMERA = 912;
    private static final int REQUEST_OCR_GALLERY = 913;
    private static final int REQUEST_EXPORT_FOLDER = 914;
    private static final String SAVED_CONVERSATION_MARKER = "[SAVED_CONVERSATION]";

    private static final String[] LANGUAGE_NAMES = {"English", "中文", "Tiếng Việt"};
    private static final String[] LANGUAGE_CODES = {"en", "zh", "vi"};

    private final int BLUE = Color.rgb(18, 103, 215);
    private final int DARK_BLUE = Color.rgb(11, 63, 148);
    private final int RED = Color.rgb(229, 28, 53);
    private final int BG = Color.rgb(244, 247, 252);
    private final int TEXT = Color.rgb(19, 35, 58);
    private final int MUTED = Color.rgb(94, 107, 125);
    private final int BORDER = Color.rgb(220, 228, 239);

    private FrameLayout content;
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, View> persistentPages = new LinkedHashMap<>();
    private String currentPage = "translate";

    private AppDatabase db;
    private TranslationManager translationManager;
    private OfflineSpeechManager offlineSpeechManager;
    private StreamingSpeechManager streamingSpeechManager;
    private GoogleOnlineSpeechManager googleOnlineSpeechManager;
    private ExportManager exportManager;
    private TextToSpeech tts;
    private TextRecognizer textRecognizer;
    private EditText activeOcrInput;
    private boolean ttsReady;

    private SpeechResultConsumer speechConsumer;
    private String speechLanguage = "en";
    private String pendingPermissionAction = "";
    private SpeechRecognizer speechRecognizer;
    private AlertDialog speechDialog;
    private TextView speechDialogText;
    private boolean speechListening;
    private boolean speechCancelledByUser;
    private boolean usingOnDeviceRecognizer;

    private MediaRecorder recorder;
    private File currentRecordingFile;
    private long currentHistoryId;
    private long recordingStartedAt;
    private long pendingDurationMs;
    private boolean isRecording;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    private EditText conversationTitle;
    private EditText conversationCategory;
    private EditText conversationTranscript;
    private EditText conversationTranslation;
    private TextView conversationPinyin;
    private TextView recordingTimer;
    private TextView conversationStatus;
    private Button startRecordingButton;
    private Button stopRecordingButton;
    private Button saveRecordingButton;
    private Button conversationSwapButton;
    private ProgressBar conversationMicLevel;
    private TextView conversationLiveOriginal;
    private TextView conversationLiveTranslation;
    private TextView conversationLivePinyin;
    private CheckBox conversationAutoSpeak;
    private String liveLanguageA = "en";
    private String liveLanguageB = "zh";
    private String livePartialText = "";
    private String livePartialLanguage = "en";
    private String lastPreviewSource = "";
    private boolean liveTranslationBusy;
    private boolean liveConversationActive;
    private boolean liveAudioStopped;
    private int liveLineNumber;
    private final ArrayDeque<LiveSegment> liveTranslationQueue = new ArrayDeque<>();
    private final Handler livePreviewHandler = new Handler(Looper.getMainLooper());
    private Spinner conversationSource;
    private Spinner conversationTarget;
    private Spinner conversationEngine;
    private boolean activeGoogleOnlineEngine;
    private enum LiveControlState { IDLE, PREPARING, RECORDING, STOPPING }
    private LiveControlState liveControlState = LiveControlState.IDLE;
    private long liveSessionToken;
    private final Handler liveControlHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStopWatchdog;

    private final Runnable recordingTicker = new Runnable() {
        @Override public void run() {
            if (!isRecording) return;
            long elapsed = System.currentTimeMillis() - recordingStartedAt;
            if (recordingTimer != null) recordingTimer.setText("Live translation  " + formatDuration(elapsed));
            timerHandler.postDelayed(this, 250);
        }
    };

    private static final class LiveSegment {
        final String source;
        final String target;
        final String text;
        final long startMs;
        final long endMs;
        final int lineNumber;

        LiveSegment(String source, String target, String text, long startMs, long endMs, int lineNumber) {
            this.source = source;
            this.target = target;
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.lineNumber = lineNumber;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        db = new AppDatabase(this);
        translationManager = new TranslationManager();
        offlineSpeechManager = new OfflineSpeechManager(this);
        streamingSpeechManager = new StreamingSpeechManager(this, offlineSpeechManager);
        googleOnlineSpeechManager = new GoogleOnlineSpeechManager(this);
        exportManager = new ExportManager(this);
        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        setContentView(buildShell());
        showPage("translate");
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
        }
        destroySpeechRecognizer();
        if (googleOnlineSpeechManager != null) googleOnlineSpeechManager.close();
        if (streamingSpeechManager != null) streamingSpeechManager.close();
        if (exportManager != null) exportManager.close();
        if (offlineSpeechManager != null) offlineSpeechManager.close();
        translationManager.close();
        if (textRecognizer != null) textRecognizer.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        db.close();
        super.onDestroy();
    }

    private View buildShell() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(10));
        header.setBackgroundColor(Color.WHITE);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_icon_large);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        header.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout titleBlock = vertical();
        titleBlock.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Howie Translate", 24, TEXT, true);
        TextView subtitle = text("Accurate online speech, offline translation and learning", 13, MUTED, false);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navScroll.setBackgroundColor(Color.WHITE);
        LinearLayout nav = horizontal();
        nav.setPadding(dp(10), dp(4), dp(10), dp(10));
        addNav(nav, "translate", "Translate");
        addNav(nav, "conversation", "Conversation");
        addNav(nav, "history", "History");
        addNav(nav, "saved", "Saved");
        addNav(nav, "glossary", "Glossary");
        addNav(nav, "settings", "Settings");
        navScroll.addView(nav);
        root.addView(navScroll);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void addNav(LinearLayout nav, String key, String label) {
        Button b = button(label, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.setMargins(dp(4), 0, dp(4), 0);
        nav.addView(b, lp);
        navButtons.put(key, b);
        b.setOnClickListener(v -> {
            showPage(key);
            if (isRecording && !"conversation".equals(key)) {
                toast("Live recording continues in the background.");
            }
        });
    }

    private void showPage(String page) {
        currentPage = page;
        for (Map.Entry<String, Button> e : navButtons.entrySet()) {
            boolean selected = e.getKey().equals(page);
            styleButton(e.getValue(), selected ? BLUE : Color.WHITE, selected ? Color.WHITE : DARK_BLUE,
                    selected ? BLUE : BORDER);
        }

        View pageView;
        boolean persistent = "translate".equals(page) || "conversation".equals(page);
        if (persistent) {
            pageView = persistentPages.get(page);
            if (pageView == null) {
                pageView = buildPage(page);
                persistentPages.put(page, pageView);
            }
        } else {
            pageView = buildPage(page);
        }
        if (pageView.getParent() instanceof ViewGroup) {
            ((ViewGroup) pageView.getParent()).removeView(pageView);
        }
        content.removeAllViews();
        content.addView(pageView);
    }

    private View buildPage(String page) {
        switch (page) {
            case "conversation": return buildConversationPage();
            case "history": return buildHistoryPage();
            case "saved": return buildAudioPage();
            case "glossary": return buildGlossaryPage();
            case "settings": return buildSettingsPage();
            default: return buildTranslatePage();
        }
    }

    private View buildTranslatePage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("Translate a phrase", "Type, paste or dictate in English, Chinese or Vietnamese."));

        LinearLayout languageRow = horizontal();
        Spinner source = languageSpinner(0);
        Spinner target = languageSpinner(1);
        Button swap = button("⇄", false);
        languageRow.addView(source, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(dp(58), dp(52));
        swapLp.setMargins(dp(8), 0, dp(8), 0);
        languageRow.addView(swap, swapLp);
        languageRow.addView(target, new LinearLayout.LayoutParams(0, dp(52), 1));
        page.addView(languageRow);

        EditText input = input("Type or paste a phrase…", 4);
        page.addView(input, marginTop(matchWrap(), 12));

        LinearLayout inputActions = horizontal();
        Button listen = button("🎙  Listen", false);
        Button clear = button("Clear", false);
        inputActions.addView(listen, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        clearLp.setMargins(dp(8), 0, 0, 0);
        inputActions.addView(clear, clearLp);
        page.addView(inputActions, marginTop(matchWrap(), 8));

        LinearLayout imageActions = horizontal();
        Button captureImage = button("📷 Capture Image", false);
        Button uploadImage = button("🖼 Upload Image", false);
        imageActions.addView(captureImage, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams uploadLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        uploadLp.setMargins(dp(8), 0, 0, 0);
        imageActions.addView(uploadImage, uploadLp);
        page.addView(imageActions, marginTop(matchWrap(), 8));

        Button translate = button("Translate", true);
        page.addView(translate, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)), 12));

        LinearLayout resultCard = card();
        TextView resultLabel = text("TRANSLATION", 12, BLUE, true);
        TextView result = text("Your translation will appear here.", 21, TEXT, false);
        result.setTextIsSelectable(true);
        TextView pinyin = text("", 16, RED, false);
        pinyin.setTypeface(Typeface.create("sans", Typeface.ITALIC));
        TextView status = text("", 13, MUTED, false);
        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        resultCard.addView(resultLabel);
        resultCard.addView(result, marginTop(matchWrap(), 8));
        resultCard.addView(pinyin, marginTop(matchWrap(), 6));
        resultCard.addView(status, marginTop(matchWrap(), 8));
        resultCard.addView(progress, new LinearLayout.LayoutParams(dp(30), dp(30)));
        page.addView(resultCard, marginTop(matchWrap(), 14));

        LinearLayout resultActions = horizontal();
        Button copy = button("Copy", false);
        Button speak = button("🔊 Speak", false);
        Button save = button("＋ Glossary", false);
        resultActions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, dp(48), 1);
        mid.setMargins(dp(6), 0, dp(6), 0);
        resultActions.addView(speak, mid);
        resultActions.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(resultActions, marginTop(matchWrap(), 10));

        swap.setOnClickListener(v -> {
            int a = source.getSelectedItemPosition();
            source.setSelection(target.getSelectedItemPosition());
            target.setSelection(a);
            String originalInput = input.getText().toString();
            String translated = result.getText().toString();
            if (!originalInput.isEmpty() && !translated.startsWith("Your translation")) {
                input.setText(translated);
                result.setText(originalInput);
                updatePinyin(pinyin, codeOf(target), originalInput, codeOf(source), translated);
            }
        });
        clear.setOnClickListener(v -> {
            input.setText("");
            result.setText("Your translation will appear here.");
            pinyin.setText("");
            status.setText("");
        });
        listen.setOnClickListener(v -> startSpeechInput(codeOf(source), recognised -> {
            input.setText(recognised);
            doTranslation(codeOf(source), codeOf(target), recognised, result, pinyin, status, progress);
        }));
        captureImage.setOnClickListener(v -> {
            activeOcrInput = input;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            try { startActivityForResult(intent, REQUEST_OCR_CAMERA); }
            catch (ActivityNotFoundException e) { toast("No camera app is available."); }
        });
        uploadImage.setOnClickListener(v -> {
            activeOcrInput = input;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQUEST_OCR_GALLERY);
        });
        translate.setOnClickListener(v -> doTranslation(codeOf(source), codeOf(target), input.getText().toString(),
                result, pinyin, status, progress));
        copy.setOnClickListener(v -> copyText(result.getText().toString()));
        speak.setOnClickListener(v -> speakText(result.getText().toString(), codeOf(target)));
        save.setOnClickListener(v -> {
            String original = input.getText().toString().trim();
            String translatedText = result.getText().toString().trim();
            if (original.isEmpty() || translatedText.isEmpty() || translatedText.startsWith("Your translation")) {
                toast("Translate a phrase before saving it.");
                return;
            }
            Models.GlossaryItem item = new Models.GlossaryItem();
            item.originalText = original;
            item.translatedText = translatedText;
            item.sourceLanguage = codeOf(source);
            item.targetLanguage = codeOf(target);
            item.pinyin = "zh".equals(item.targetLanguage) ? PinyinUtil.toPinyin(translatedText)
                    : ("zh".equals(item.sourceLanguage) ? PinyinUtil.toPinyin(original) : "");
            showGlossaryEditor(item, true, () -> toast("Saved to your glossary."));
        });

        return scroll(page);
    }

    private View buildConversationPage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("Live conversation", "Use Google Online speech recognition for higher accuracy and translate each completed phrase on the phone."));

        LinearLayout notice = cardTint(Color.rgb(234, 243, 255));
        notice.addView(text("Google Online is the recommended free speech engine. It listens through the phone's active Google speech service, translates each confirmed phrase with downloaded ML Kit models, and automatically resumes listening until you press Stop. Google Online saves the transcript to History; use Offline Whisper when retaining original audio is essential.", 14, DARK_BLUE, false));
        page.addView(notice);

        conversationTitle = input("Recording title (for example: Supplier meeting)", 1);
        conversationCategory = input("Category (for example: Supplier Meetings)", 1);
        page.addView(conversationTitle, marginTop(matchWrap(), 12));
        page.addView(conversationCategory, marginTop(matchWrap(), 8));

        LinearLayout languageRow = horizontal();
        conversationSource = languageSpinner(0);
        conversationTarget = languageSpinner(1);
        conversationSwapButton = button("⇄", false);
        languageRow.addView(conversationSource, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams swapLp = new LinearLayout.LayoutParams(dp(58), dp(52));
        swapLp.setMargins(dp(7), 0, dp(7), 0);
        languageRow.addView(conversationSwapButton, swapLp);
        languageRow.addView(conversationTarget, new LinearLayout.LayoutParams(0, dp(52), 1));
        page.addView(languageRow, marginTop(matchWrap(), 8));

        LinearLayout engineRow = horizontal();
        TextView engineLabel = text("Speech engine", 14, TEXT, true);
        engineLabel.setGravity(Gravity.CENTER_VERTICAL);
        conversationEngine = new Spinner(this);
        String[] engineOptions = {"Google Online – auto-select service", "Offline Whisper"};
        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, engineOptions);
        conversationEngine.setAdapter(engineAdapter);
        conversationEngine.setSelection(0);
        engineRow.addView(engineLabel, new LinearLayout.LayoutParams(0, dp(52), 0.8f));
        LinearLayout.LayoutParams engineLp = new LinearLayout.LayoutParams(0, dp(52), 1.7f);
        engineLp.setMargins(dp(8), 0, 0, 0);
        engineRow.addView(conversationEngine, engineLp);
        page.addView(engineRow, marginTop(matchWrap(), 8));

        conversationAutoSpeak = new CheckBox(this);
        conversationAutoSpeak.setText("Read each translated phrase aloud");
        conversationAutoSpeak.setTextColor(TEXT);
        conversationAutoSpeak.setTextSize(14);
        conversationAutoSpeak.setChecked(false);
        page.addView(conversationAutoSpeak, marginTop(matchWrap(), 5));

        LinearLayout recorderCard = card();
        recordingTimer = text("Ready for a live conversation", 21, TEXT, true);
        recordingTimer.setGravity(Gravity.CENTER);
        conversationStatus = text("Choose a language pair, then tap Start live. For accuracy, speech is locked to the language on the left. Tap ⇄ when the other person speaks.", 13, MUTED, false);
        conversationStatus.setGravity(Gravity.CENTER);
        conversationMicLevel = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        conversationMicLevel.setMax(100);
        conversationMicLevel.setProgress(0);
        LinearLayout controls = horizontal();
        startRecordingButton = button("●  Start live", true);
        stopRecordingButton = button("■  Stop", false);
        startRecordingButton.setEnabled(true);
        stopRecordingButton.setEnabled(false);
        controls.addView(startRecordingButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(52), 1);
        stopLp.setMargins(dp(8), 0, 0, 0);
        controls.addView(stopRecordingButton, stopLp);
        recorderCard.addView(recordingTimer);
        recorderCard.addView(conversationStatus, marginTop(matchWrap(), 6));
        recorderCard.addView(conversationMicLevel, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)), 12));
        recorderCard.addView(controls, marginTop(matchWrap(), 14));
        page.addView(recorderCard, marginTop(matchWrap(), 12));

        LinearLayout liveCard = cardTint(Color.WHITE);
        liveCard.addView(text("LIVE ORIGINAL", 12, BLUE, true));
        conversationLiveOriginal = text("Waiting for speech…", 21, TEXT, true);
        conversationLiveOriginal.setTextIsSelectable(true);
        liveCard.addView(conversationLiveOriginal, marginTop(matchWrap(), 7));
        conversationLivePinyin = text("", 15, RED, false);
        conversationLivePinyin.setTypeface(Typeface.create("sans", Typeface.ITALIC));
        liveCard.addView(conversationLivePinyin, marginTop(matchWrap(), 4));
        liveCard.addView(text("LIVE TRANSLATION", 12, RED, true), marginTop(matchWrap(), 14));
        conversationLiveTranslation = text("The translation will appear here as each phrase is completed.", 19, TEXT, false);
        conversationLiveTranslation.setTextIsSelectable(true);
        liveCard.addView(conversationLiveTranslation, marginTop(matchWrap(), 7));
        page.addView(liveCard, marginTop(matchWrap(), 12));

        TextView transcriptLabel = text("COMPLETE ORIGINAL TRANSCRIPT", 12, BLUE, true);
        conversationTranscript = input("Numbered, timestamped original lines will build automatically…", 7);
        page.addView(transcriptLabel, marginTop(matchWrap(), 14));
        page.addView(conversationTranscript, marginTop(matchWrap(), 5));

        TextView translationLabel = text("COMPLETE TRANSLATION", 12, RED, true);
        conversationTranslation = input("Matching numbered translation lines will build automatically…", 7);
        conversationPinyin = text("", 15, RED, false);
        conversationPinyin.setTypeface(Typeface.create("sans", Typeface.ITALIC));
        page.addView(translationLabel, marginTop(matchWrap(), 14));
        page.addView(conversationTranslation, marginTop(matchWrap(), 5));
        page.addView(conversationPinyin, marginTop(matchWrap(), 5));

        saveRecordingButton = button("Save to Saved", true);
        saveRecordingButton.setEnabled(currentRecordingFile != null && !isRecording);
        Button newConversation = button("New conversation", false);
        Button addGlossary = button("Add selected transcript text to Glossary", false);
        LinearLayout saveRow = horizontal();
        saveRow.addView(saveRecordingButton, new LinearLayout.LayoutParams(0, dp(54), 1.4f));
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(0, dp(54), 1);
        newLp.setMargins(dp(8), 0, 0, 0);
        saveRow.addView(newConversation, newLp);
        page.addView(saveRow, marginTop(matchWrap(), 14));
        page.addView(addGlossary, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)), 8));

        conversationSwapButton.setOnClickListener(v -> swapConversationLanguages());
        startRecordingButton.setOnClickListener(v -> requestStartRecording());
        stopRecordingButton.setOnClickListener(v -> stopRecording());
        saveRecordingButton.setOnClickListener(v -> savePendingRecording());
        newConversation.setOnClickListener(v -> confirmNewConversation());
        updateLiveControlUi();
        addGlossary.setOnClickListener(v -> {
            String original = selectedOrAll(conversationTranscript);
            String translated = selectedOrAll(conversationTranslation);
            if (original.isEmpty() || translated.isEmpty()) {
                toast("Select or enter both the original phrase and its translation first.");
                return;
            }
            Models.GlossaryItem item = new Models.GlossaryItem();
            item.originalText = removeTranscriptLabels(original);
            item.translatedText = removeTranscriptLabels(translated);
            item.sourceLanguage = codeOf(conversationSource);
            item.targetLanguage = codeOf(conversationTarget);
            String chinese = "zh".equals(item.targetLanguage) ? translated : ("zh".equals(item.sourceLanguage) ? original : "");
            item.pinyin = PinyinUtil.toPinyin(removeTranscriptLabels(chinese));
            item.category = clean(conversationCategory.getText().toString(), "Conversations");
            showGlossaryEditor(item, true, () -> toast("Saved to your glossary."));
        });

        return scroll(page);
    }

    private View buildAudioPage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("Saved", "Keep important conversations and recordings, then replay, re-translate or export them."));
        LinearLayout savedHint = cardTint(Color.rgb(234, 243, 255));
        savedHint.addView(text("Saved conversations can include transcript-only items or linked audio recordings.", 14, DARK_BLUE, false));
        page.addView(savedHint);

        LinearLayout tools = horizontal();
        EditText search = input("Search title, category or subtitles…", 1);
        Button newRecording = button("＋ Record", true);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(dp(118), dp(52));
        newLp.setMargins(dp(8), 0, 0, 0);
        tools.addView(newRecording, newLp);
        page.addView(tools);

        LinearLayout list = vertical();
        page.addView(list, marginTop(matchWrap(), 12));
        renderAudioList(list, "");
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { renderAudioList(list, s.toString()); }
        });
        newRecording.setOnClickListener(v -> showPage("conversation"));
        return scroll(page);
    }

    private void renderAudioList(LinearLayout list, String query) {
        list.removeAllViews();
        List<Models.RecordingItem> items = db.getRecordings(query);
        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No saved items found", 19, TEXT, true));
            empty.addView(text("Save a conversation or recording from History and it will appear here.", 14, MUTED, false), marginTop(matchWrap(), 5));
            list.addView(empty);
            return;
        }
        String lastCategory = null;
        for (Models.RecordingItem item : items) {
            if (!item.category.equalsIgnoreCase(lastCategory == null ? "" : lastCategory)) {
                TextView heading = text(item.category, 15, DARK_BLUE, true);
                list.addView(heading, marginTop(matchWrap(), lastCategory == null ? 0 : 14));
                lastCategory = item.category;
            }
            LinearLayout card = card();
            TextView title = text(item.title, 18, TEXT, true);
            TextView meta = text(languageName(item.sourceLanguage) + " → " + languageName(item.targetLanguage)
                    + "  •  " + formatDuration(item.durationMs) + "  •  " + formatDate(item.createdAt), 12, MUTED, false);
            TextView preview = text(ellipsize(item.transcript.isEmpty() ? "No subtitle added" : ensureTranscriptNumbering(item.transcript), 150), 14, TEXT, false);
            card.addView(title);
            card.addView(meta, marginTop(matchWrap(), 4));
            card.addView(preview, marginTop(matchWrap(), 8));

            LinearLayout actions = horizontal();
            Button play = button("▶ Open", false);
            Button edit = button("Edit", false);
            Button delete = button("Delete", false);
            actions.addView(play, new LinearLayout.LayoutParams(0, dp(44), 1));
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, dp(44), 1);
            editLp.setMargins(dp(6), 0, dp(6), 0);
            actions.addView(edit, editLp);
            actions.addView(delete, new LinearLayout.LayoutParams(0, dp(44), 1));
            card.addView(actions, marginTop(matchWrap(), 10));

            LinearLayout exports = horizontal();
            Button mp3 = button("⬇ MP3", false);
            Button karaoke = button("🎬 Karaoke MP4", true);
            exports.addView(mp3, new LinearLayout.LayoutParams(0, dp(46), 1));
            LinearLayout.LayoutParams karaokeLp = new LinearLayout.LayoutParams(0, dp(46), 1.35f);
            karaokeLp.setMargins(dp(7), 0, 0, 0);
            exports.addView(karaoke, karaokeLp);
            card.addView(exports, marginTop(matchWrap(), 7));
            list.addView(card, marginTop(matchWrap(), 7));

            play.setOnClickListener(v -> showAudioPlayer(item));
            edit.setOnClickListener(v -> showRecordingEditor(item, () -> renderAudioList(list, query)));
            delete.setOnClickListener(v -> confirmDeleteRecording(item, () -> renderAudioList(list, query)));
            mp3.setOnClickListener(v -> exportMp3(item));
            karaoke.setOnClickListener(v -> exportKaraokeMp4(item));
        }
    }

    private View buildHistoryPage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("History", "Every typed translation and live conversation is saved automatically. Re-translate, save or keep the recording from here."));

        LinearLayout hint = cardTint(Color.rgb(234, 243, 255));
        hint.addView(text("Press and hold the ☰ Drag button, then drop a conversation onto another record to change its order.", 14, DARK_BLUE, false));
        page.addView(hint);

        EditText search = input("Search conversation titles, categories or transcript text…", 1);
        page.addView(search, marginTop(matchWrap(), 10));
        LinearLayout list = vertical();
        page.addView(list, marginTop(matchWrap(), 12));
        renderHistoryList(list, "");
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable value) {
                renderHistoryList(list, value.toString());
            }
        });
        return scroll(page);
    }

    private void renderHistoryList(LinearLayout list, String query) {
        list.removeAllViews();
        List<Models.RecordingItem> items = db.getHistory(query);
        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No conversation history yet", 19, TEXT, true));
            empty.addView(text("Typed translations and completed live conversations will appear here automatically.", 14, MUTED, false), marginTop(matchWrap(), 5));
            list.addView(empty);
            return;
        }

        Map<Long, Integer> chronologicalNumbers = new HashMap<>();
        List<Models.RecordingItem> chronological = new ArrayList<>(items);
        chronological.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        for (int i = 0; i < chronological.size(); i++) chronologicalNumbers.put(chronological.get(i).id, i + 1);

        int position = 1;
        for (Models.RecordingItem item : items) {
            LinearLayout recordCard = card();
            LinearLayout top = horizontal();
            TextView title = text(chronologicalNumbers.get(item.id) + ".  " + item.title, 18, TEXT, true);
            Button drag = button("☰ Drag", false);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(drag, new LinearLayout.LayoutParams(dp(92), dp(42)));
            recordCard.addView(top);
            recordCard.addView(text(item.category + "  •  " + languageName(item.sourceLanguage) + " → "
                    + languageName(item.targetLanguage) + "  •  " + formatDuration(item.durationMs), 12, MUTED, false), marginTop(matchWrap(), 4));
            recordCard.addView(text(formatDate(item.createdAt), 12, MUTED, false), marginTop(matchWrap(), 2));
            String previewText = item.transcript == null || item.transcript.trim().isEmpty()
                    ? "No original transcript" : ensureTranscriptNumbering(item.transcript);
            recordCard.addView(text(ellipsize(previewText, 190), 14, TEXT, false), marginTop(matchWrap(), 8));

            LinearLayout actions = horizontal();
            Button open = button("Open", true);
            Button retranslate = button("Translate", false);
            Button saveItem = button("Save", false);
            actions.addView(open, new LinearLayout.LayoutParams(0, dp(45), 1));
            LinearLayout.LayoutParams translateLp = new LinearLayout.LayoutParams(0, dp(45), 1);
            translateLp.setMargins(dp(6), 0, dp(6), 0);
            actions.addView(retranslate, translateLp);
            actions.addView(saveItem, new LinearLayout.LayoutParams(0, dp(45), 1));
            recordCard.addView(actions, marginTop(matchWrap(), 10));

            LinearLayout manageActions = horizontal();
            Button edit = button("Edit", false);
            Button delete = button("Delete", false);
            manageActions.addView(edit, new LinearLayout.LayoutParams(0, dp(42), 1));
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, dp(42), 1);
            deleteLp.setMargins(dp(7), 0, 0, 0);
            manageActions.addView(delete, deleteLp);
            recordCard.addView(manageActions, marginTop(matchWrap(), 7));
            list.addView(recordCard, marginTop(matchWrap(), position == 1 ? 0 : 8));

            open.setOnClickListener(v -> showAudioPlayer(item));
            retranslate.setOnClickListener(v -> showRetranslateDialog(item));
            saveItem.setOnClickListener(v -> showSaveHistoryDialog(item, () -> renderHistoryList(list, query)));
            edit.setOnClickListener(v -> showRecordingEditor(item, () -> renderHistoryList(list, query)));
            delete.setOnClickListener(v -> confirmDeleteRecording(item, () -> renderHistoryList(list, query)));
            drag.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("Howie Translate conversation", String.valueOf(item.id));
                v.startDragAndDrop(data, new View.DragShadowBuilder(recordCard), null, 0);
                return true;
            });
            recordCard.setOnDragListener((v, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        v.setAlpha(0.58f);
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        v.setAlpha(1f);
                        return true;
                    case DragEvent.ACTION_DROP:
                        v.setAlpha(1f);
                        try {
                            CharSequence value = event.getClipData().getItemAt(0).coerceToText(this);
                            long draggedId = Long.parseLong(value.toString());
                            db.reorderRecording(draggedId, item.id);
                            renderHistoryList(list, query);
                        } catch (Exception e) {
                            toast("The conversation could not be reordered.");
                        }
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        v.setAlpha(1f);
                        return true;
                    default:
                        return true;
                }
            });
            position++;
        }
    }

    private View buildGlossaryPage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("Glossary", "Organise useful words and phrases into categories, then edit, copy or practise them."));

        LinearLayout tools = horizontal();
        EditText search = input("Search words, pinyin, notes or categories…", 1);
        Button add = button("＋ Add", true);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(94), dp(52));
        addLp.setMargins(dp(8), 0, 0, 0);
        tools.addView(add, addLp);
        page.addView(tools);

        LinearLayout list = vertical();
        page.addView(list, marginTop(matchWrap(), 12));
        renderGlossary(list, "");
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { renderGlossary(list, s.toString()); }
        });
        add.setOnClickListener(v -> {
            Models.GlossaryItem item = new Models.GlossaryItem();
            showGlossaryEditor(item, true, () -> renderGlossary(list, search.getText().toString()));
        });
        return scroll(page);
    }

    private void renderGlossary(LinearLayout list, String query) {
        list.removeAllViews();
        List<Models.GlossaryItem> items = db.getGlossary(query);
        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Your glossary is ready", 19, TEXT, true));
            empty.addView(text("Save a translated phrase or create an entry here.", 14, MUTED, false), marginTop(matchWrap(), 5));
            list.addView(empty);
            return;
        }
        String lastCategory = null;
        for (Models.GlossaryItem item : items) {
            if (!item.category.equalsIgnoreCase(lastCategory == null ? "" : lastCategory)) {
                TextView heading = text(item.category, 15, DARK_BLUE, true);
                list.addView(heading, marginTop(matchWrap(), lastCategory == null ? 0 : 14));
                lastCategory = item.category;
            }
            LinearLayout card = card();
            LinearLayout top = horizontal();
            TextView original = text(item.originalText, 18, TEXT, true);
            TextView star = text(item.favorite ? "★" : "☆", 26, item.favorite ? RED : MUTED, false);
            star.setGravity(Gravity.CENTER);
            top.addView(original, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(star, new LinearLayout.LayoutParams(dp(44), dp(44)));
            card.addView(top);
            if (!item.pinyin.isEmpty()) {
                TextView py = text(item.pinyin, 15, RED, false);
                py.setTypeface(Typeface.create("sans", Typeface.ITALIC));
                card.addView(py, marginTop(matchWrap(), 2));
            }
            card.addView(text(item.translatedText, 16, TEXT, false), marginTop(matchWrap(), 6));
            card.addView(text(languageName(item.sourceLanguage) + " → " + languageName(item.targetLanguage), 12, MUTED, false), marginTop(matchWrap(), 5));
            if (!item.notes.isEmpty()) card.addView(text(item.notes, 13, MUTED, false), marginTop(matchWrap(), 6));

            LinearLayout actions = horizontal();
            Button speak = button("🔊", false);
            Button copy = button("Copy", false);
            Button edit = button("Edit", false);
            Button delete = button("Delete", false);
            actions.addView(speak, new LinearLayout.LayoutParams(0, dp(42), 0.7f));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(42), 1);
            ap.setMargins(dp(5), 0, 0, 0);
            actions.addView(copy, ap);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(42), 1);
            ep.setMargins(dp(5), 0, 0, 0);
            actions.addView(edit, ep);
            LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(0, dp(42), 1);
            dpLp.setMargins(dp(5), 0, 0, 0);
            actions.addView(delete, dpLp);
            card.addView(actions, marginTop(matchWrap(), 10));
            list.addView(card, marginTop(matchWrap(), 7));

            star.setOnClickListener(v -> {
                item.favorite = !item.favorite;
                db.updateGlossary(item);
                renderGlossary(list, query);
            });
            speak.setOnClickListener(v -> speakText(item.translatedText, item.targetLanguage));
            copy.setOnClickListener(v -> copyText(item.originalText + "\n" + item.pinyin + "\n" + item.translatedText));
            edit.setOnClickListener(v -> showGlossaryEditor(item, false, () -> renderGlossary(list, query)));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete glossary entry?")
                    .setMessage("This will remove the phrase from your glossary.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (d, w) -> {
                        db.deleteGlossary(item.id);
                        renderGlossary(list, query);
                    }).show());
        }
    }

    private View buildSettingsPage() {
        LinearLayout page = pageContainer();
        page.addView(sectionTitle("Settings", "Test Google Online speech, prepare offline translation and manage the Android installation."));

        LinearLayout modelCard = card();
        modelCard.addView(text("Offline translation models", 19, TEXT, true));
        modelCard.addView(text("Download the English, Chinese and Vietnamese translation models once while connected to Wi-Fi. Typed translation can then run on the phone without an internet connection.", 14, MUTED, false), marginTop(matchWrap(), 6));
        TextView modelStatus = text("Not checked yet", 13, DARK_BLUE, true);
        Button download = button("Download all translation models", true);
        modelCard.addView(modelStatus, marginTop(matchWrap(), 10));
        modelCard.addView(download, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)), 10));
        page.addView(modelCard);

        LinearLayout speechCard = card();
        speechCard.addView(text("Speech recognition", 19, TEXT, true));
        speechCard.addView(text("Google Online is the recommended free mode and uses the speech-recognition service already installed on the phone. It needs an internet connection but no Cloud API key or paid account. Offline Whisper remains built in as a fallback.", 14, MUTED, false), marginTop(matchWrap(), 6));
        TextView allSpeechStatus = text("Google Online recommended • Offline Whisper included ✓", 13, Color.rgb(25, 125, 75), true);
        Button downloadAllSpeech = button("No speech API account required ✓", false);
        downloadAllSpeech.setEnabled(false);
        speechCard.addView(allSpeechStatus, marginTop(matchWrap(), 10));
        speechCard.addView(downloadAllSpeech, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)), 8));
        speechCard.addView(buildSpeechModelRow("en"), marginTop(matchWrap(), 12));
        speechCard.addView(buildSpeechModelRow("zh"), marginTop(matchWrap(), 8));
        speechCard.addView(buildSpeechModelRow("vi"), marginTop(matchWrap(), 8));

        LinearLayout testRow = horizontal();
        Spinner testLanguage = languageSpinner(1);
        Button testSpeech = button("Test Google Online", false);
        testRow.addView(testLanguage, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(0, dp(50), 1.15f);
        testLp.setMargins(dp(8), 0, 0, 0);
        testRow.addView(testSpeech, testLp);
        speechCard.addView(testRow, marginTop(matchWrap(), 12));
        page.addView(speechCard, marginTop(matchWrap(), 10));

        LinearLayout storageCard = card();
        storageCard.addView(text("Storage and uninstall", 19, TEXT, true));
        storageCard.addView(text("The app is a standard Android installation. It does not use device-administrator access. Android can uninstall it normally. Uninstalling removes its private database, recordings, translation packs and the bundled Whisper model.", 14, MUTED, false), marginTop(matchWrap(), 6));
        Button appInfo = button("Open App Info / Uninstall", false);
        storageCard.addView(appInfo, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)), 10));
        page.addView(storageCard, marginTop(matchWrap(), 10));

        LinearLayout about = cardTint(Color.rgb(255, 244, 246));
        about.addView(text("Howie Translate 0.8.0 Saved + OCR preview", 17, RED, true));
        about.addView(text("Google Online now detects every installed Android speech service, prefers Google, checks language support where available, and automatically changes service if one appears to listen but returns no microphone activity. Start and Stop retain the explicit state machine and watchdog. Offline Whisper remains available when original audio retention is required.", 14, TEXT, false), marginTop(matchWrap(), 6));
        page.addView(about, marginTop(matchWrap(), 10));

        download.setOnClickListener(v -> {
            download.setEnabled(false);
            modelStatus.setText("Starting downloads…");
            translationManager.prepareAll(new TranslationManager.PreparationCallback() {
                @Override public void onProgress(String message) { runOnUiThread(() -> modelStatus.setText(message)); }
                @Override public void onComplete() { runOnUiThread(() -> {
                    modelStatus.setText("All offline translation models are ready ✓");
                    download.setEnabled(true);
                    toast("Offline translation models are ready.");
                }); }
                @Override public void onError(String message) { runOnUiThread(() -> {
                    modelStatus.setText(message);
                    download.setEnabled(true);
                }); }
            });
        });

        testSpeech.setOnClickListener(v -> {
            String language = codeOf(testLanguage);
            startSpeechInput(language, heard -> new AlertDialog.Builder(this)
                    .setTitle("Google Online speech is working")
                    .setMessage("I heard:\n\n" + heard)
                    .setPositiveButton("OK", null)
                    .show());
        });

        appInfo.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        return scroll(page);
    }

    private View buildSpeechModelRow(String language) {
        OfflineSpeechManager.ModelInfo info = OfflineSpeechManager.modelInfo(language);
        LinearLayout block = vertical();
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(rounded(Color.rgb(247, 250, 255), BORDER, 14));

        TextView title = text(info.displayName + " speech", 16, TEXT, true);
        TextView status = text("Included in the multilingual Whisper model ✓", 13,
                Color.rgb(25, 125, 75), false);
        block.addView(title);
        block.addView(status, marginTop(matchWrap(), 3));
        return block;
    }

    private void downloadSpeechModel(String language, TextView status, Button download, Button remove) {
        OfflineSpeechManager.ModelInfo info = OfflineSpeechManager.modelInfo(language);
        download.setEnabled(false);
        remove.setEnabled(false);
        status.setText("Preparing download…");
        status.setTextColor(DARK_BLUE);
        offlineSpeechManager.download(language, new OfflineSpeechManager.DownloadCallback() {
            @Override public void onProgress(String message, int percent) {
                status.setText(percent >= 0 ? message + "  " + percent + "%" : message);
            }

            @Override public void onComplete() {
                status.setText(offlineSpeechManager.status(language));
                status.setTextColor(Color.rgb(25, 125, 75));
                download.setText("Installed ✓");
                download.setEnabled(false);
                remove.setEnabled(true);
                toast(info.displayName + " offline speech is ready.");
            }

            @Override public void onError(String message) {
                status.setText(message);
                status.setTextColor(RED);
                download.setText("Try download again");
                download.setEnabled(true);
                remove.setEnabled(offlineSpeechManager.isInstalled(language));
            }
        });
    }

    private void downloadSpeechModelsSequentially(String[] languages, int index, TextView status, Button button) {
        if (index >= languages.length) {
            status.setText("All three offline speech models are ready ✓");
            status.setTextColor(Color.rgb(25, 125, 75));
            button.setEnabled(true);
            button.setText("All speech models installed ✓");
            toast("Offline English, Chinese and Vietnamese speech models are ready.");
            return;
        }
        String language = languages[index];
        OfflineSpeechManager.ModelInfo info = OfflineSpeechManager.modelInfo(language);
        if (offlineSpeechManager.isInstalled(language)) {
            downloadSpeechModelsSequentially(languages, index + 1, status, button);
            return;
        }
        offlineSpeechManager.download(language, new OfflineSpeechManager.DownloadCallback() {
            @Override public void onProgress(String message, int percent) {
                status.setText("Model " + (index + 1) + " of " + languages.length + ": " + message
                        + (percent >= 0 ? "  " + percent + "%" : ""));
            }

            @Override public void onComplete() {
                status.setText(info.displayName + " is ready. Continuing…");
                downloadSpeechModelsSequentially(languages, index + 1, status, button);
            }

            @Override public void onError(String message) {
                status.setText(message);
                status.setTextColor(RED);
                button.setEnabled(true);
                button.setText("Try downloading all again");
            }
        });
    }

    private void doTranslation(String source, String target, String input, TextView result, TextView pinyin,
                               TextView status, ProgressBar progress) {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        status.setText("Preparing…");
        translationManager.translate(source, target, input, new TranslationManager.Callback() {
            @Override public void onStatus(String message) { runOnUiThread(() -> status.setText(message)); }
            @Override public void onSuccess(String translatedText) { runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                result.setText(translatedText);
                updatePinyin(pinyin, source, input, target, translatedText);
                autoSaveTypedHistory(source, target, input, translatedText);
                status.setText("Translated and saved to History.");
            }); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                status.setText(message);
                toast("Translation could not be completed.");
            }); }
        });
    }

    private void updatePinyin(TextView view, String source, String sourceText, String target, String targetText) {
        String chinese = "zh".equals(target) ? targetText : ("zh".equals(source) ? sourceText : "");
        view.setText(PinyinUtil.toPinyin(chinese));
        view.setVisibility(chinese.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void requestStartRecording() {
        if (liveControlState != LiveControlState.IDLE) {
            toast(liveControlState == LiveControlState.STOPPING
                    ? "Please wait for the current recording to stop."
                    : "The conversation is already starting or recording.");
            return;
        }
        liveSessionToken++;
        liveControlState = LiveControlState.PREPARING;
        updateLiveControlUi();
        if (conversationStatus != null) conversationStatus.setText("Preparing the microphone and translation models…");

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "record";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startRecordingInternal(liveSessionToken);
    }

    private void startRecordingInternal() {
        startRecordingInternal(liveSessionToken);
    }

    private void startRecordingInternal(long token) {
        if (token != liveSessionToken || liveControlState != LiveControlState.PREPARING) return;
        String source = codeOf(conversationSource);
        String target = codeOf(conversationTarget);
        if (source.equals(target)) {
            resetLiveControlsToIdle("Choose two different languages.");
            toast("Choose two different languages.");
            return;
        }
        liveLanguageA = source;
        liveLanguageB = target;
        boolean useGoogle = conversationEngine == null || conversationEngine.getSelectedItemPosition() == 0;
        activeGoogleOnlineEngine = useGoogle;

        if (useGoogle) {
            if (!googleOnlineSpeechManager.isSupported()) {
                resetLiveControlsToIdle("Google Online speech recognition is not available. Choose Offline Whisper instead.");
                new AlertDialog.Builder(this)
                        .setTitle("Google Online unavailable")
                        .setMessage("Enable or update Speech Recognition & Synthesis from Google, or choose Offline Whisper from the Speech engine menu.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            prepareLiveTranslationAndStart(source, target, token);
        } else {
            ensureLiveSpeechModels(source, target, () -> {
                if (token == liveSessionToken && liveControlState == LiveControlState.PREPARING) {
                    prepareLiveTranslationAndStart(source, target, token);
                }
            });
        }
    }

    private void ensureLiveSpeechModels(String source, String target, Runnable ready) {
        List<String> missing = new ArrayList<>();
        if (!offlineSpeechManager.isInstalled(source)) missing.add(source);
        if (!offlineSpeechManager.isInstalled(target)) missing.add(target);
        if (missing.isEmpty()) {
            ready.run();
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String language : missing) {
            if (names.length() > 0) names.append(" and ");
            names.append(OfflineSpeechManager.modelInfo(language).displayName);
        }
        new AlertDialog.Builder(this)
                .setTitle("Offline speech models required")
                .setMessage("Live two-way conversation needs the " + names + " speech model"
                        + (missing.size() > 1 ? "s" : "") + ". Download now? Each model is kept privately inside the app and only needs to be downloaded once.")
                .setNegativeButton("Not now", (dialog, which) -> resetLiveControlsToIdle("Start cancelled."))
                .setPositiveButton("Download", (dialog, which) -> downloadSpeechModelsSequentially(missing, 0, ready))
                .show();
    }

    private void downloadSpeechModelsSequentially(List<String> languages, int index, Runnable ready) {
        if (index >= languages.size()) {
            ready.run();
            return;
        }
        String language = languages.get(index);
        OfflineSpeechManager.ModelInfo info = OfflineSpeechManager.modelInfo(language);
        LinearLayout body = vertical();
        TextView status = text("Connecting…", 15, TEXT, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        body.addView(status);
        body.addView(progress, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)), 10));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Downloading " + info.displayName + " speech")
                .setView(wrapDialog(body))
                .setCancelable(false)
                .create();
        dialog.show();
        offlineSpeechManager.download(language, new OfflineSpeechManager.DownloadCallback() {
            @Override public void onProgress(String message, int percent) {
                status.setText(message);
                if (percent >= 0) {
                    progress.setIndeterminate(false);
                    progress.setProgress(percent);
                } else {
                    progress.setIndeterminate(true);
                }
            }

            @Override public void onComplete() {
                dialog.dismiss();
                downloadSpeechModelsSequentially(languages, index + 1, ready);
            }

            @Override public void onError(String message) {
                dialog.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Speech model download failed")
                        .setMessage(message)
                        .setNegativeButton("Cancel", (d, w) -> resetLiveControlsToIdle("Start cancelled."))
                        .setPositiveButton("Try again", (d, w) -> downloadSpeechModelsSequentially(languages, index, ready))
                        .show();
            }
        });
    }

    private void prepareLiveTranslationAndStart(String source, String target, long token) {
        if (token != liveSessionToken || liveControlState != LiveControlState.PREPARING) return;
        if (conversationStatus != null) conversationStatus.setText("Preparing offline translation models…");
        translationManager.preparePair(source, target, new TranslationManager.PreparationCallback() {
            @Override public void onProgress(String message) {
                runOnUiThread(() -> {
                    if (token == liveSessionToken && liveControlState == LiveControlState.PREPARING && conversationStatus != null) {
                        conversationStatus.setText(message);
                    }
                });
            }

            @Override public void onComplete() {
                runOnUiThread(() -> {
                    if (token == liveSessionToken && liveControlState == LiveControlState.PREPARING) {
                        beginLiveConversation(source, target, token);
                    }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (token != liveSessionToken) return;
                    resetLiveControlsToIdle(message);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Translation model unavailable")
                            .setMessage(message + "\n\nConnect to the internet once so the language models can be downloaded, then try again.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void beginLiveConversation(String source, String target, long token) {
        if (token != liveSessionToken || liveControlState != LiveControlState.PREPARING) return;
        if (currentRecordingFile != null && currentRecordingFile.exists() && currentHistoryId == 0) currentRecordingFile.delete();
        currentHistoryId = 0;
        if (activeGoogleOnlineEngine) {
            // Google Online must own the microphone directly for reliable recognition. The live
            // transcript is saved to History, but a reusable source-audio file is not guaranteed.
            currentRecordingFile = null;
        } else {
            File dir = new File(getFilesDir(), "recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                resetLiveControlsToIdle("The recording folder could not be created.");
                toast("The recording folder could not be created.");
                return;
            }
            currentRecordingFile = new File(dir, "conversation_" + System.currentTimeMillis() + ".wav");
        }
        pendingDurationMs = 0;
        recordingStartedAt = System.currentTimeMillis();
        isRecording = true;
        liveConversationActive = true;
        liveAudioStopped = false;
        liveTranslationBusy = false;
        liveTranslationQueue.clear();
        liveLineNumber = 0;
        livePartialText = "";
        lastPreviewSource = "";
        conversationTranscript.setText("");
        conversationTranslation.setText("");
        conversationPinyin.setText("");
        conversationLiveOriginal.setText("Listening…");
        conversationLiveTranslation.setText("Waiting for the first phrase…");
        conversationLivePinyin.setText("");
        conversationMicLevel.setProgress(0);
        saveRecordingButton.setEnabled(false);
        conversationSource.setEnabled(false);
        conversationTarget.setEnabled(false);
        if (conversationEngine != null) conversationEngine.setEnabled(false);
        recordingTimer.setText("Live translation  00:00");
        liveControlState = LiveControlState.RECORDING;
        updateLiveControlUi();
        timerHandler.post(recordingTicker);
        livePreviewHandler.post(livePreviewTicker);
        // Create the History entry immediately, before speech recognition starts. This ensures a
        // live conversation remains visible in History even if the speech service returns no
        // result, the user changes pages, or Android interrupts the app.
        persistLiveHistory(true);

        StreamingSpeechManager.Callback liveCallback = new StreamingSpeechManager.Callback() {
            @Override public void onPreparing(String message) {
                if (token != liveSessionToken) return;
                if (conversationStatus != null) conversationStatus.setText(message + " Speak naturally and pause briefly between ideas.");
            }

            @Override public void onAudioLevel(int percent) {
                if (token != liveSessionToken) return;
                if (conversationMicLevel != null) conversationMicLevel.setProgress(percent);
                if (percent > 7 && conversationStatus != null
                        && conversationStatus.getText().toString().toLowerCase(Locale.ROOT).contains("listening")) {
                    conversationStatus.setText("Microphone active • listening in the selected source language");
                }
            }

            @Override public void onPartial(String language, String text) {
                if (token != liveSessionToken || liveControlState == LiveControlState.IDLE) return;
                livePartialLanguage = language;
                livePartialText = text == null ? "" : text.trim();
                if (livePartialText.isEmpty()) {
                    conversationLiveOriginal.setText("Listening…");
                    conversationLivePinyin.setText("");
                    return;
                }
                int previewLineNumber = liveLineNumber + 1;
                conversationLiveOriginal.setText(previewLineNumber + ". " + livePartialText);
                conversationLivePinyin.setText("zh".equals(language)
                        ? previewLineNumber + ". " + PinyinUtil.toPinyin(livePartialText) : "");
            }

            @Override public void onSegment(String language, String text, long startMs, long endMs) {
                if (token != liveSessionToken) return;
                String cleanText = text == null ? "" : text.trim();
                if (cleanText.isEmpty()) return;
                String targetLanguage = otherLiveLanguage(language);
                int lineNumber = ++liveLineNumber;
                appendTranscriptLine(conversationTranscript, lineNumber, startMs, languageName(language), cleanText);
                conversationLiveOriginal.setText(lineNumber + ". " + cleanText);
                conversationLivePinyin.setText("zh".equals(language)
                        ? lineNumber + ". " + PinyinUtil.toPinyin(cleanText) : "");
                livePartialText = "";
                lastPreviewSource = "";
                persistLiveHistory(false);
                enqueueLiveTranslation(new LiveSegment(language, targetLanguage, cleanText, startMs, endMs, lineNumber));
            }

            @Override public void onError(String message) {
                if (token != liveSessionToken) return;
                if (conversationStatus != null) conversationStatus.setText(message);
                if (liveControlState == LiveControlState.RECORDING) {
                    toast("Speech recognition stopped. The audio recorded so far will be retained.");
                    stopRecording();
                }
            }

            @Override public void onStopped(File audioFile, long durationMs) {
                if (token != liveSessionToken) return;
                finishLiveRecordingUi(durationMs, audioFile);
            }
        };

        if (activeGoogleOnlineEngine) {
            googleOnlineSpeechManager.startConversation(source, target, currentRecordingFile,
                    buildSpeechBiases(source, target), liveCallback);
        } else {
            streamingSpeechManager.startConversation(source, target, currentRecordingFile, liveCallback);
        }
    }

    private final Runnable livePreviewTicker = new Runnable() {
        @Override public void run() {
            if (!liveConversationActive) return;
            String partial = livePartialText == null ? "" : livePartialText.trim();
            String language = livePartialLanguage;
            if (!partial.isEmpty() && !partial.equals(lastPreviewSource) && !liveTranslationBusy) {
                lastPreviewSource = partial;
                int generation = partial.hashCode();
                String target = otherLiveLanguage(language);
                translationManager.translatePrepared(language, target, partial, new TranslationManager.Callback() {
                    @Override public void onStatus(String status) { }
                    @Override public void onSuccess(String translatedText) {
                        runOnUiThread(() -> {
                            if (liveConversationActive && livePartialText != null && livePartialText.hashCode() == generation) {
                                conversationLiveTranslation.setText((liveLineNumber + 1) + ". " + translatedText);
                            }
                        });
                    }
                    @Override public void onError(String message) { }
                });
            }
            livePreviewHandler.postDelayed(this, 700);
        }
    };

    private void enqueueLiveTranslation(LiveSegment segment) {
        liveTranslationQueue.add(segment);
        processNextLiveTranslation();
    }

    private void processNextLiveTranslation() {
        if (liveTranslationBusy) return;
        LiveSegment segment = liveTranslationQueue.poll();
        if (segment == null) {
            maybeEnableLiveSave();
            return;
        }
        liveTranslationBusy = true;
        conversationLiveTranslation.setText(segment.lineNumber + ". Translating…");
        translationManager.translatePrepared(segment.source, segment.target, segment.text, new TranslationManager.Callback() {
            @Override public void onStatus(String status) { }

            @Override public void onSuccess(String translatedText) {
                runOnUiThread(() -> {
                    appendTranscriptLine(conversationTranslation, segment.lineNumber, segment.startMs, languageName(segment.target), translatedText);
                    conversationLiveTranslation.setText(segment.lineNumber + ". " + translatedText);
                    String chinese = "zh".equals(segment.target) ? translatedText : ("zh".equals(segment.source) ? segment.text : "");
                    if (!chinese.isEmpty()) appendPinyinLine(segment.lineNumber, segment.startMs, PinyinUtil.toPinyin(chinese));
                    persistLiveHistory(false);
                    if (conversationAutoSpeak != null && conversationAutoSpeak.isChecked()) {
                        speakText(translatedText, segment.target);
                    }
                    liveTranslationBusy = false;
                    processNextLiveTranslation();
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    appendTranscriptLine(conversationTranslation, segment.lineNumber, segment.startMs, languageName(segment.target), "[Translation unavailable]");
                    conversationStatus.setText(message);
                    persistLiveHistory(false);
                    liveTranslationBusy = false;
                    processNextLiveTranslation();
                });
            }
        });
    }

    private void maybeEnableLiveSave() {
        if (!liveAudioStopped || liveTranslationBusy || !liveTranslationQueue.isEmpty()) return;
        autoSaveLiveHistory();
        boolean ready = currentRecordingFile != null && currentRecordingFile.exists();
        if (saveRecordingButton != null) saveRecordingButton.setEnabled(ready);
        if (conversationStatus != null) {
            conversationStatus.setText(ready
                    ? "Saved automatically to History. Tap Save to Saved to keep it in Audio."
                    : "Saved automatically to History. This speech service did not provide a reusable audio recording on this device.");
        }
    }

    private void appendTranscriptLine(EditText field, int lineNumber, long timestampMs, String speakerLanguage, String value) {
        String line = lineNumber + ". [" + formatDuration(timestampMs) + "] " + speakerLanguage + ": " + value;
        String current = field.getText().toString();
        field.setText(current.isEmpty() ? line : current + "\n\n" + line);
        field.setSelection(field.length());
    }

    private void appendPinyinLine(int lineNumber, long timestampMs, String pinyin) {
        if (pinyin == null || pinyin.trim().isEmpty()) return;
        String line = lineNumber + ". [" + formatDuration(timestampMs) + "] " + pinyin.trim();
        String current = conversationPinyin.getText().toString();
        conversationPinyin.setText(current.isEmpty() ? line : current + "\n" + line);
    }

    private String otherLiveLanguage(String source) {
        return source.equals(liveLanguageA) ? liveLanguageB : liveLanguageA;
    }

    private void swapConversationLanguages() {
        int sourcePosition = conversationSource.getSelectedItemPosition();
        int targetPosition = conversationTarget.getSelectedItemPosition();
        conversationSource.setSelection(targetPosition);
        conversationTarget.setSelection(sourcePosition);
        if (liveConversationActive) {
            String newSource = codeOf(conversationSource);
            if (activeGoogleOnlineEngine) googleOnlineSpeechManager.switchLanguage(newSource);
            else streamingSpeechManager.switchLanguage(newSource);
            livePartialText = "";
            conversationLiveOriginal.setText("Switching to " + languageName(newSource) + "…");
            conversationLiveTranslation.setText("Listening for the other speaker…");
            conversationStatus.setText("Language direction switched • now listening for " + languageName(newSource));
        }
    }

    private void stopRecording() {
        if (liveControlState == LiveControlState.IDLE) {
            toast("There is no active live conversation.");
            return;
        }
        if (liveControlState == LiveControlState.STOPPING) return;

        if (liveControlState == LiveControlState.PREPARING) {
            liveSessionToken++;
            pendingPermissionAction = "";
            if (googleOnlineSpeechManager != null) googleOnlineSpeechManager.forceStop();
            if (streamingSpeechManager != null) streamingSpeechManager.stop();
            resetLiveControlsToIdle("Start cancelled.");
            return;
        }

        liveControlState = LiveControlState.STOPPING;
        updateLiveControlUi();
        if (conversationStatus != null) conversationStatus.setText(activeGoogleOnlineEngine
                ? "Stopping Google Online and finalising the transcript…"
                : "Stopping the microphone and finalising the recorded audio…");
        if (recordingTimer != null) recordingTimer.setText("Stopping…");
        livePreviewHandler.removeCallbacks(livePreviewTicker);
        if (activeGoogleOnlineEngine) googleOnlineSpeechManager.stop();
        else streamingSpeechManager.stop();

        if (liveStopWatchdog != null) liveControlHandler.removeCallbacks(liveStopWatchdog);
        final long token = liveSessionToken;
        liveStopWatchdog = () -> {
            if (token != liveSessionToken || liveControlState != LiveControlState.STOPPING) return;
            if (activeGoogleOnlineEngine) googleOnlineSpeechManager.forceStop();
            else streamingSpeechManager.stop();
            long elapsed = recordingStartedAt == 0 ? 0 : Math.max(0, System.currentTimeMillis() - recordingStartedAt);
            finishLiveRecordingUi(elapsed, currentRecordingFile);
            if (conversationStatus != null) {
                conversationStatus.setText(activeGoogleOnlineEngine
                        ? "Google Online stopped. The transcript captured so far was retained in History."
                        : "Recording stopped. The speech service did not return a final callback, but the audio captured so far was retained.");
            }
        };
        liveControlHandler.postDelayed(liveStopWatchdog, 6500);
    }

    private void finishLiveRecordingUi(long durationMs, File audioFile) {
        if (liveStopWatchdog != null) {
            liveControlHandler.removeCallbacks(liveStopWatchdog);
            liveStopWatchdog = null;
        }
        pendingDurationMs = Math.max(0, durationMs);
        if (audioFile != null && audioFile.exists()) currentRecordingFile = audioFile;
        isRecording = false;
        liveConversationActive = false;
        liveAudioStopped = true;
        liveControlState = LiveControlState.IDLE;
        timerHandler.removeCallbacks(recordingTicker);
        livePreviewHandler.removeCallbacks(livePreviewTicker);
        if (conversationMicLevel != null) conversationMicLevel.setProgress(0);
        if (recordingTimer != null) recordingTimer.setText((activeGoogleOnlineEngine ? "Conversation  " : "Recorded  ")
                + formatDuration(pendingDurationMs));
        if (conversationStatus != null) conversationStatus.setText(activeGoogleOnlineEngine
                ? "Conversation stopped. The transcript will be saved automatically to History."
                : "Conversation stopped. Review the transcript and save it to Saved.");
        if (saveRecordingButton != null) saveRecordingButton.setEnabled(false);
        if (conversationSource != null) conversationSource.setEnabled(true);
        if (conversationTarget != null) conversationTarget.setEnabled(true);
        if (conversationEngine != null) conversationEngine.setEnabled(true);
        updateLiveControlUi();
        persistLiveHistory(false);
        maybeEnableLiveSave();
    }

    private void resetLiveControlsToIdle(String message) {
        liveControlState = LiveControlState.IDLE;
        isRecording = false;
        liveConversationActive = false;
        if (liveStopWatchdog != null) {
            liveControlHandler.removeCallbacks(liveStopWatchdog);
            liveStopWatchdog = null;
        }
        if (conversationSource != null) conversationSource.setEnabled(true);
        if (conversationTarget != null) conversationTarget.setEnabled(true);
        if (conversationEngine != null) conversationEngine.setEnabled(true);
        if (conversationStatus != null && message != null && !message.trim().isEmpty()) conversationStatus.setText(message);
        updateLiveControlUi();
    }

    private void updateLiveControlUi() {
        if (startRecordingButton == null || stopRecordingButton == null) return;
        switch (liveControlState) {
            case PREPARING:
                startRecordingButton.setEnabled(false);
                startRecordingButton.setText("Preparing…");
                stopRecordingButton.setEnabled(true);
                stopRecordingButton.setText("Cancel");
                break;
            case RECORDING:
                startRecordingButton.setEnabled(false);
                startRecordingButton.setText("●  Recording");
                stopRecordingButton.setEnabled(true);
                stopRecordingButton.setText("■  Stop");
                break;
            case STOPPING:
                startRecordingButton.setEnabled(false);
                startRecordingButton.setText("Stopping…");
                stopRecordingButton.setEnabled(false);
                stopRecordingButton.setText("Please wait");
                break;
            default:
                startRecordingButton.setEnabled(true);
                startRecordingButton.setText("●  Start live");
                stopRecordingButton.setEnabled(false);
                stopRecordingButton.setText("■  Stop");
                break;
        }
    }

    private List<String> buildSpeechBiases(String... languages) {
        List<String> result = new ArrayList<>();
        List<Models.GlossaryItem> items = db.getGlossary("");
        for (Models.GlossaryItem item : items) {
            for (String language : languages) {
                if (result.size() >= 50) return result;
                String value = language.equals(item.sourceLanguage) ? item.originalText
                        : (language.equals(item.targetLanguage) ? item.translatedText : "");
                value = value == null ? "" : value.trim();
                if (!value.isEmpty() && value.length() <= 80 && !result.contains(value)) result.add(value);
            }
        }
        return result;
    }

    private void savePendingRecording() {
        if (currentRecordingFile == null || !currentRecordingFile.exists()) {
            toast("Record some audio first.");
            return;
        }
        Models.RecordingItem item = findCurrentHistoryItem();
        if (item == null) item = new Models.RecordingItem();
        item.title = clean(conversationTitle.getText().toString(), "Conversation " + formatDate(System.currentTimeMillis()));
        item.category = clean(conversationCategory.getText().toString(), "General");
        item.path = currentRecordingFile != null && currentRecordingFile.exists()
                ? currentRecordingFile.getAbsolutePath() : "";
        item.sourceLanguage = liveLanguageA;
        item.targetLanguage = liveLanguageB;
        item.transcript = conversationTranscript.getText().toString().trim();
        item.translation = conversationTranslation.getText().toString().trim();
        item.pinyin = conversationPinyin.getText().toString().trim();
        item.durationMs = pendingDurationMs;
        item.notes = item.notes == null ? "" : item.notes.replace(HISTORY_ONLY_MARKER, "");
        if (item.id == 0) item.id = db.insertRecording(item); else db.updateRecording(item);
        currentRecordingFile = null;
        currentHistoryId = 0;
        pendingDurationMs = 0;
        recordingTimer.setText("Saved  " + formatDuration(item.durationMs));
        conversationStatus.setText("Saved. The History record remains linked to this conversation.");
        saveRecordingButton.setEnabled(false);
        toast("Conversation saved.");
    }

    private void confirmNewConversation() {
        if (isRecording || liveConversationActive) {
            toast("Stop the live recording before starting a new conversation.");
            return;
        }
        boolean hasContent = currentRecordingFile != null
                || (conversationTranscript != null && !conversationTranscript.getText().toString().trim().isEmpty())
                || (conversationTranslation != null && !conversationTranslation.getText().toString().trim().isEmpty());
        if (!hasContent) {
            resetConversationPage();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start a new conversation?")
                .setMessage(currentRecordingFile != null
                        ? "The current recording has not been saved. Starting a new conversation will discard it."
                        : "The current results will be cleared from the Conversation page. Saved History and Saved records will not be affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start new", (d, w) -> resetConversationPage())
                .show();
    }

    private void resetConversationPage() {
        if (currentRecordingFile != null && currentRecordingFile.exists() && currentHistoryId == 0) currentRecordingFile.delete();
        currentRecordingFile = null;
        currentHistoryId = 0;
        pendingDurationMs = 0;
        liveLineNumber = 0;
        livePartialText = "";
        liveTranslationQueue.clear();
        if (conversationTitle != null) conversationTitle.setText("");
        if (conversationCategory != null) conversationCategory.setText("");
        if (conversationTranscript != null) conversationTranscript.setText("");
        if (conversationTranslation != null) conversationTranslation.setText("");
        if (conversationPinyin != null) conversationPinyin.setText("");
        if (conversationLiveOriginal != null) conversationLiveOriginal.setText("Waiting for speech…");
        if (conversationLiveTranslation != null) conversationLiveTranslation.setText("The translation will appear here as each phrase is completed.");
        if (conversationLivePinyin != null) conversationLivePinyin.setText("");
        if (conversationMicLevel != null) conversationMicLevel.setProgress(0);
        if (recordingTimer != null) recordingTimer.setText("Ready for a live conversation");
        if (conversationStatus != null) conversationStatus.setText("Choose a language pair, then tap Start live.");
        if (saveRecordingButton != null) saveRecordingButton.setEnabled(false);
    }

    private String selectedOrAll(EditText field) {
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (start >= 0 && end > start) return field.getText().subSequence(start, end).toString().trim();
        return field.getText().toString().trim();
    }

    private String removeTranscriptLabels(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?m)^\\s*\\d+\\.\\s*", "")
                .replaceAll("\\[[0-9:]+\\]\\s*[^:]+:\\s*", " ")
                .trim();
    }

    private void autoSaveTypedHistory(String source, String target, String original, String translated) {
        if (original == null || translated == null || original.trim().isEmpty() || translated.trim().isEmpty()) return;
        Models.RecordingItem item = new Models.RecordingItem();
        item.title = "Typed translation " + formatDate(System.currentTimeMillis());
        item.category = "Typed Translations";
        item.path = "";
        item.sourceLanguage = source;
        item.targetLanguage = target;
        item.transcript = "1. [00:00] " + languageName(source) + ": " + original.trim();
        item.translation = "1. [00:00] " + languageName(target) + ": " + translated.trim();
        String chinese = "zh".equals(target) ? translated : ("zh".equals(source) ? original : "");
        item.pinyin = chinese.isEmpty() ? "" : "1. [00:00] " + PinyinUtil.toPinyin(chinese);
        item.notes = HISTORY_ONLY_MARKER;
        db.insertRecording(item);
    }

    private void autoSaveLiveHistory() {
        persistLiveHistory(false);
    }

    private void persistLiveHistory(boolean forceCreate) {
        if (!forceCreate && currentHistoryId == 0) return;
        Models.RecordingItem item = new Models.RecordingItem();
        if (currentHistoryId != 0) {
            Models.RecordingItem existing = findCurrentHistoryItem();
            if (existing != null) item = existing;
        }
        item.title = clean(conversationTitle.getText().toString(), "Conversation " + formatDate(System.currentTimeMillis()));
        item.category = clean(conversationCategory.getText().toString(), "General");
        item.path = currentRecordingFile != null && currentRecordingFile.exists()
                ? currentRecordingFile.getAbsolutePath() : "";
        item.sourceLanguage = liveLanguageA;
        item.targetLanguage = liveLanguageB;
        item.transcript = conversationTranscript.getText().toString().trim();
        item.translation = conversationTranslation.getText().toString().trim();
        item.pinyin = conversationPinyin.getText().toString().trim();
        item.durationMs = pendingDurationMs > 0 ? pendingDurationMs
                : Math.max(0L, recordingStartedAt == 0L ? 0L : System.currentTimeMillis() - recordingStartedAt);
        item.notes = HISTORY_ONLY_MARKER;
        if (item.id == 0) {
            currentHistoryId = db.insertRecording(item);
        } else {
            db.updateRecording(item);
            currentHistoryId = item.id;
        }
    }

    private Models.RecordingItem findCurrentHistoryItem() {
        if (currentHistoryId == 0) return null;
        for (Models.RecordingItem item : db.getHistory("")) {
            if (item.id == currentHistoryId) return item;
        }
        return null;
    }

    private void showTextHistory(Models.RecordingItem item) {
        LinearLayout body = dialogBody();
        body.addView(text("ORIGINAL", 11, BLUE, true));
        body.addView(text(item.transcript == null || item.transcript.isEmpty() ? "No original text." : ensureTranscriptNumbering(item.transcript), 18, TEXT, true), marginTop(matchWrap(), 5));
        if (item.pinyin != null && !item.pinyin.isEmpty()) {
            TextView py = text(ensureTranscriptNumbering(item.pinyin), 15, RED, false);
            py.setTypeface(Typeface.create("sans", Typeface.ITALIC));
            body.addView(py, marginTop(matchWrap(), 5));
        }
        body.addView(text("TRANSLATION", 11, RED, true), marginTop(matchWrap(), 14));
        body.addView(text(item.translation == null || item.translation.isEmpty() ? "No translation." : ensureTranscriptNumbering(item.translation), 16, TEXT, false), marginTop(matchWrap(), 5));
        new AlertDialog.Builder(this).setTitle(item.title).setView(wrapDialog(body)).setPositiveButton("Close", null).show();
    }

    private String ensureTranscriptNumbering(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String[] lines = value.trim().split("\\n+");
        StringBuilder numbered = new StringBuilder();
        int number = 1;
        for (String line : lines) {
            String cleanLine = line.trim();
            if (cleanLine.isEmpty()) continue;
            cleanLine = cleanLine.replaceFirst("^\\s*\\d+\\.\\s*", "");
            if (numbered.length() > 0) numbered.append("\n\n");
            numbered.append(number).append(". ").append(cleanLine);
            number++;
        }
        return numbered.toString();
    }

    private void showAudioPlayer(Models.RecordingItem item) {
        File file = item.path == null || item.path.trim().isEmpty() ? null : new File(item.path);
        if (file == null || !file.isFile()) {
            showTextHistory(item);
            return;
        }
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(10), dp(18), 0);
        TextView original = text(item.transcript.isEmpty() ? "No original subtitle has been added." : ensureTranscriptNumbering(item.transcript), 18, TEXT, true);
        TextView py = text(ensureTranscriptNumbering(item.pinyin), 15, RED, false);
        py.setTypeface(Typeface.create("sans", Typeface.ITALIC));
        TextView translated = text(item.translation.isEmpty() ? "No translated subtitle has been added." : ensureTranscriptNumbering(item.translation), 16, TEXT, false);
        body.addView(text("ORIGINAL SUBTITLE", 11, BLUE, true));
        body.addView(original, marginTop(matchWrap(), 5));
        if (!item.pinyin.isEmpty()) body.addView(py, marginTop(matchWrap(), 5));
        body.addView(text("TRANSLATION", 11, RED, true), marginTop(matchWrap(), 14));
        body.addView(translated, marginTop(matchWrap(), 5));

        SeekBar seek = new SeekBar(this);
        TextView time = text("00:00 / " + formatDuration(item.durationMs), 13, MUTED, false);
        time.setGravity(Gravity.CENTER);
        body.addView(seek, marginTop(matchWrap(), 14));
        body.addView(time);

        LinearLayout controls = horizontal();
        Button back = button("−10s", false);
        Button play = button("▶ Play", true);
        Button forward = button("+10s", false);
        Button speed = button("1.0×", false);
        controls.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(0, dp(48), 1.2f);
        c.setMargins(dp(5), 0, dp(5), 0);
        controls.addView(play, c);
        controls.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(0, dp(48), 1);
        s.setMargins(dp(5), 0, 0, 0);
        controls.addView(speed, s);
        body.addView(controls, marginTop(matchWrap(), 10));

        LinearLayout exportRow = horizontal();
        Button mp3 = button("⬇ Export MP3", false);
        Button karaoke = button("🎬 Karaoke MP4", true);
        exportRow.addView(mp3, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams karaokeLp = new LinearLayout.LayoutParams(0, dp(48), 1.15f);
        karaokeLp.setMargins(dp(7), 0, 0, 0);
        exportRow.addView(karaoke, karaokeLp);
        body.addView(exportRow, marginTop(matchWrap(), 12));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        MediaPlayer player = new MediaPlayer();
        Handler handler = new Handler(Looper.getMainLooper());
        final float[] playbackSpeed = {1f};
        final Runnable[] updater = new Runnable[1];
        try {
            player.setDataSource(item.path);
            player.prepare();
            seek.setMax(player.getDuration());
            time.setText("00:00 / " + formatDuration(player.getDuration()));
        } catch (IOException e) {
            toast("Audio could not be opened.");
            player.release();
            return;
        }
        updater[0] = () -> {
            if (!player.isPlaying()) return;
            int pos = player.getCurrentPosition();
            seek.setProgress(pos);
            time.setText(formatDuration(pos) + " / " + formatDuration(player.getDuration()));
            handler.postDelayed(updater[0], 300);
        };
        play.setOnClickListener(v -> {
            if (player.isPlaying()) {
                player.pause();
                play.setText("▶ Play");
            } else {
                player.start();
                play.setText("❚❚ Pause");
                handler.post(updater[0]);
            }
        });
        back.setOnClickListener(v -> player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)));
        forward.setOnClickListener(v -> player.seekTo(Math.min(player.getDuration(), player.getCurrentPosition() + 10000)));
        speed.setOnClickListener(v -> {
            float next = playbackSpeed[0] == 1f ? 0.75f : playbackSpeed[0] == 0.75f ? 0.5f : 1f;
            playbackSpeed[0] = next;
            try {
                player.setPlaybackParams(new PlaybackParams().setSpeed(next));
                speed.setText(next + "×");
            } catch (Exception e) {
                toast("Playback speed is not supported on this phone.");
            }
        });
        mp3.setOnClickListener(v -> exportMp3(item));
        karaoke.setOnClickListener(v -> exportKaraokeMp4(item));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) player.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        player.setOnCompletionListener(mp -> {
            play.setText("▶ Play");
            seek.setProgress(0);
            time.setText("00:00 / " + formatDuration(player.getDuration()));
        });
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
        });
        dialog.show();
    }

    private void exportMp3(Models.RecordingItem item) {
        startExport(item, false);
    }

    private void exportKaraokeMp4(Models.RecordingItem item) {
        if (item.transcript == null || item.transcript.trim().isEmpty()) {
            toast("A numbered transcript is required to create a karaoke video.");
            return;
        }
        startExport(item, true);
    }

    private void startExport(Models.RecordingItem item, boolean karaoke) {
        LinearLayout body = dialogBody();
        TextView status = text(karaoke ? "Preparing the karaoke video…" : "Preparing the MP3…", 15, TEXT, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(2);
        body.addView(status);
        body.addView(progress, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)), 12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(karaoke ? "Creating Karaoke MP4" : "Exporting MP3")
                .setView(wrapDialog(body))
                .setCancelable(false)
                .create();
        dialog.show();

        ExportManager.Callback callback = new ExportManager.Callback() {
            @Override public void onProgress(String message, int percent) {
                status.setText(message);
                progress.setProgress(percent);
            }

            @Override public void onSuccess(Uri uri, String displayName) {
                dialog.dismiss();
                showExportComplete(uri, displayName, karaoke ? "video/mp4" : "audio/mpeg");
            }

            @Override public void onError(String message) {
                dialog.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Export could not be completed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        };
        if (karaoke) exportManager.exportKaraokeMp4(item, callback); else exportManager.exportMp3(item, callback);
    }

    private void showExportComplete(Uri uri, String displayName, String mimeType) {
        new AlertDialog.Builder(this)
                .setTitle("Export saved")
                .setMessage(displayName + " was saved in Downloads/Howie Translate.")
                .setNegativeButton("Close", null)
                .setNeutralButton("Share", (d, w) -> shareExport(uri, mimeType))
                .setPositiveButton("Open", (d, w) -> openExport(uri, mimeType))
                .show();
    }

    private void openExport(Uri uri, String mimeType) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("No app is available to open this exported file.");
        }
    }

    private void shareExport(Uri uri, String mimeType) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share export"));
        } catch (ActivityNotFoundException e) {
            toast("No sharing app is available.");
        }
    }

    private void showRecordingEditor(Models.RecordingItem item, Runnable afterSave) {
        LinearLayout body = dialogBody();
        EditText title = input("Title", 1); title.setText(item.title);
        EditText category = input("Category", 1); category.setText(item.category);
        Spinner source = languageSpinner(indexOfCode(item.sourceLanguage));
        Spinner target = languageSpinner(indexOfCode(item.targetLanguage));
        EditText transcript = input("Original subtitle", 4); transcript.setText(ensureTranscriptNumbering(item.transcript));
        EditText translation = input("Translated subtitle", 4); translation.setText(ensureTranscriptNumbering(item.translation));
        EditText notes = input("Notes", 3); notes.setText(item.notes);
        body.addView(title);
        body.addView(category, marginTop(matchWrap(), 7));
        LinearLayout langs = horizontal();
        langs.addView(source, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView arrow = text("→", 22, MUTED, true); arrow.setGravity(Gravity.CENTER);
        langs.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(50)));
        langs.addView(target, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(langs, marginTop(matchWrap(), 7));
        body.addView(transcript, marginTop(matchWrap(), 7));
        body.addView(translation, marginTop(matchWrap(), 7));
        body.addView(notes, marginTop(matchWrap(), 7));
        new AlertDialog.Builder(this)
                .setTitle("Edit recording")
                .setView(wrapDialog(body))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    item.title = clean(title.getText().toString(), "Untitled recording");
                    item.category = clean(category.getText().toString(), "General");
                    item.sourceLanguage = codeOf(source);
                    item.targetLanguage = codeOf(target);
                    item.transcript = ensureTranscriptNumbering(transcript.getText().toString());
                    item.translation = ensureTranscriptNumbering(translation.getText().toString());
                    item.pinyin = "zh".equals(item.targetLanguage) ? PinyinUtil.toPinyin(item.translation)
                            : ("zh".equals(item.sourceLanguage) ? PinyinUtil.toPinyin(item.transcript) : "");
                    item.notes = notes.getText().toString().trim();
                    db.updateRecording(item);
                    afterSave.run();
                }).show();
    }

    private void confirmDeleteRecording(Models.RecordingItem item, Runnable afterDelete) {
        new AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage("This permanently deletes this History or Audio record and any linked audio file from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    if (item.path != null && !item.path.trim().isEmpty()) {
                        File f = new File(item.path);
                        if (f.isFile()) f.delete();
                    }
                    db.deleteRecording(item.id);
                    afterDelete.run();
                }).show();
    }

    private void showGlossaryEditor(Models.GlossaryItem item, boolean isNew, Runnable afterSave) {
        LinearLayout body = dialogBody();
        Spinner source = languageSpinner(indexOfCode(item.sourceLanguage));
        Spinner target = languageSpinner(indexOfCode(item.targetLanguage));
        LinearLayout langs = horizontal();
        langs.addView(source, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView arrow = text("→", 22, MUTED, true); arrow.setGravity(Gravity.CENTER);
        langs.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(50)));
        langs.addView(target, new LinearLayout.LayoutParams(0, dp(50), 1));
        EditText original = input("Original word or phrase", 3); original.setText(item.originalText);
        EditText translated = input("Translation", 3); translated.setText(item.translatedText);
        EditText category = input("Category", 1); category.setText(item.category);
        EditText notes = input("Notes", 3); notes.setText(item.notes);
        CheckBox favorite = new CheckBox(this); favorite.setText("Favourite"); favorite.setChecked(item.favorite);
        body.addView(langs);
        body.addView(original, marginTop(matchWrap(), 7));
        body.addView(translated, marginTop(matchWrap(), 7));
        body.addView(category, marginTop(matchWrap(), 7));
        body.addView(notes, marginTop(matchWrap(), 7));
        body.addView(favorite, marginTop(matchWrap(), 7));

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "Add glossary entry" : "Edit glossary entry")
                .setView(wrapDialog(body))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    item.sourceLanguage = codeOf(source);
                    item.targetLanguage = codeOf(target);
                    item.originalText = original.getText().toString().trim();
                    item.translatedText = translated.getText().toString().trim();
                    item.category = clean(category.getText().toString(), "General");
                    item.notes = notes.getText().toString().trim();
                    item.favorite = favorite.isChecked();
                    String chinese = "zh".equals(item.targetLanguage) ? item.translatedText
                            : ("zh".equals(item.sourceLanguage) ? item.originalText : "");
                    item.pinyin = PinyinUtil.toPinyin(chinese);
                    if (item.originalText.isEmpty() && item.translatedText.isEmpty()) {
                        toast("The glossary entry was empty and was not saved.");
                        return;
                    }
                    if (isNew) item.id = db.insertGlossary(item); else db.updateGlossary(item);
                    afterSave.run();
                }).show();
    }

    private void startSpeechInput(String language, SpeechResultConsumer consumer) {
        speechLanguage = language;
        speechConsumer = consumer;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "speech";
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        launchSpeechRecognizer();
    }

    private void launchSpeechRecognizer() {
        destroySpeechRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechConsumer = null;
            new AlertDialog.Builder(this)
                    .setTitle("Google speech recognition unavailable")
                    .setMessage("This phone does not currently expose a speech-recognition service. Enable or update Speech Recognition & Synthesis from Google, then try again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        speechCancelledByUser = false;
        showSpeechListeningDialog();
        speechListening = true;

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    updateSpeechDialog("Listening • speak naturally…");
                }

                @Override public void onBeginningOfSpeech() {
                    updateSpeechDialog("Hearing " + languageName(speechLanguage) + "…");
                }

                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    updateSpeechDialog("Finishing the phrase…");
                }

                @Override public void onError(int error) {
                    boolean cancelled = speechCancelledByUser;
                    speechListening = false;
                    SpeechResultConsumer consumer = speechConsumer;
                    speechConsumer = null;
                    destroySpeechRecognizer();
                    dismissSpeechDialog();
                    if (cancelled) return;
                    String message = speechRecognizerError(error);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Speech could not be recognised")
                            .setMessage(message + "\n\nCheck the internet connection and confirm that "
                                    + languageName(speechLanguage) + " is selected before speaking.")
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Try again", (dialog, which) -> {
                                speechConsumer = consumer;
                                launchSpeechRecognizer();
                            })
                            .show();
                }

                @Override public void onResults(Bundle results) {
                    ArrayList<String> values = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String heard = values == null || values.isEmpty() ? "" : values.get(0).trim();
                    SpeechResultConsumer consumer = speechConsumer;
                    speechConsumer = null;
                    speechListening = false;
                    destroySpeechRecognizer();
                    dismissSpeechDialog();
                    if (heard.isEmpty()) {
                        toast("I could not recognise that phrase. Please try again.");
                    } else if (consumer != null) {
                        consumer.accept(heard);
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> values = partialResults == null ? null
                            : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values != null && !values.isEmpty() && !values.get(0).trim().isEmpty()) {
                        updateSpeechDialog(values.get(0).trim());
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag(speechLanguage));
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag(speechLanguage));
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            if (Build.VERSION.SDK_INT >= 33) {
                ArrayList<String> biases = new ArrayList<>(buildSpeechBiases(speechLanguage));
                if (!biases.isEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biases);
            }
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            speechListening = false;
            destroySpeechRecognizer();
            dismissSpeechDialog();
            speechConsumer = null;
            new AlertDialog.Builder(this)
                    .setTitle("Speech could not start")
                    .setMessage(e.getMessage() == null ? "The phone's speech service could not start." : e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showSpeechListeningDialog() {
        LinearLayout body = vertical();
        body.setPadding(dp(4), dp(4), dp(4), 0);
        speechDialogText = text("Starting Google Online speech…", 19, TEXT, true);
        speechDialogText.setGravity(Gravity.CENTER);
        TextView mode = text("Online recognition • language locked to " + languageName(speechLanguage),
                13, MUTED, false);
        mode.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        body.addView(speechDialogText);
        body.addView(mode, marginTop(matchWrap(), 8));
        LinearLayout progressRow = horizontal();
        progressRow.setGravity(Gravity.CENTER);
        progressRow.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        body.addView(progressRow, marginTop(matchWrap(), 10));

        speechDialog = new AlertDialog.Builder(this)
                .setTitle("Speak now")
                .setView(wrapDialog(body))
                .setNegativeButton("Cancel", (dialog, which) -> cancelSpeechRecognition())
                .create();
        speechDialog.setOnCancelListener(dialog -> cancelSpeechRecognition());
        speechDialog.show();
    }

    private String speechRecognizerError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "The phone could not access the microphone audio.";
            case SpeechRecognizer.ERROR_CLIENT: return "The phone's speech service stopped unexpectedly.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission is missing.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "The selected language is not supported by the active speech service.";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "The selected language is temporarily unavailable.";
            case SpeechRecognizer.ERROR_NETWORK: return "The network connection failed.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "The speech service timed out while using the network.";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No confident speech match was found.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "The speech recognizer is busy. Wait a moment and try again.";
            case SpeechRecognizer.ERROR_SERVER: return "The speech service returned an error.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech was heard before the listening period ended.";
            default:
                if (Build.VERSION.SDK_INT >= 31 && error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                    return "The speech service received too many requests. Wait briefly and try again.";
                }
                return "Speech recognition failed with error code " + error + ".";
        }
    }

    private void updateSpeechDialog(String message) {
        runOnUiThread(() -> {
            if (speechDialogText != null) speechDialogText.setText(message);
        });
    }

    private void cancelSpeechRecognition() {
        speechCancelledByUser = true;
        speechListening = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        destroySpeechRecognizer();
        dismissSpeechDialog();
        speechConsumer = null;
    }

    private void destroySpeechRecognizer() {
        speechListening = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
    }

    private void dismissSpeechDialog() {
        if (speechDialog != null) {
            try { speechDialog.dismiss(); } catch (Exception ignored) { }
            speechDialog = null;
        }
        speechDialogText = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                if ("record".equals(pendingPermissionAction)) resetLiveControlsToIdle("Microphone permission is required to start a live conversation.");
                toast("Microphone permission is required for recording and dictation.");
                pendingPermissionAction = "";
                return;
            }
            if ("record".equals(pendingPermissionAction)) startRecordingInternal();
            else if ("speech".equals(pendingPermissionAction)) launchSpeechRecognizer();
            pendingPermissionAction = "";
        }
    }

    private void speakText(String value, String language) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.startsWith("Your translation")) {
            toast("There is no phrase to read aloud.");
            return;
        }
        if (!ttsReady) {
            toast("Text-to-speech is still starting.");
            return;
        }
        int result = tts.setLanguage(Locale.forLanguageTag(localeTag(language)));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("A voice for this language is not installed on the phone.");
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "howie_translate_phrase");
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Howie Translate", text));
        toast("Copied.");
    }

    private LinearLayout sectionTitle(String title, String subtitle) {
        LinearLayout box = vertical();
        box.setPadding(0, 0, 0, dp(12));
        box.addView(text(title, 25, TEXT, true));
        box.addView(text(subtitle, 14, MUTED, false), marginTop(matchWrap(), 4));
        return box;
    }

    private LinearLayout pageContainer() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(16), dp(16), dp(28));
        return page;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(child, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card() { return cardTint(Color.WHITE); }

    private LinearLayout cardTint(int color) {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(rounded(color, BORDER, 14));
        return card;
    }

    private LinearLayout dialogBody() {
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(8), dp(18), dp(8));
        return body;
    }

    private ScrollView wrapDialog(View body) {
        ScrollView s = new ScrollView(this);
        s.addView(body);
        return s;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.08f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText input(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setTextColor(TEXT);
        e.setHintTextColor(Color.rgb(138, 150, 166));
        e.setGravity(lines > 1 ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        e.setMinLines(lines);
        e.setMaxLines(Math.max(lines, lines == 1 ? 1 : 8));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(rounded(Color.WHITE, BORDER, 10));
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        styleButton(b, primary ? BLUE : Color.WHITE, primary ? Color.WHITE : DARK_BLUE, primary ? BLUE : BORDER);
        return b;
    }

    private void styleButton(Button button, int background, int foreground, int border) {
        button.setTextColor(foreground);
        button.setBackground(rounded(background, border, 10));
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private Spinner languageSpinner(int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LANGUAGE_NAMES);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selected, LANGUAGE_NAMES.length - 1)));
        spinner.setBackground(rounded(Color.WHITE, BORDER, 10));
        spinner.setPadding(dp(10), 0, dp(6), 0);
        return spinner;
    }

    private String codeOf(Spinner spinner) {
        int index = spinner.getSelectedItemPosition();
        return LANGUAGE_CODES[Math.max(0, Math.min(index, LANGUAGE_CODES.length - 1))];
    }

    private int indexOfCode(String code) {
        for (int i = 0; i < LANGUAGE_CODES.length; i++) if (LANGUAGE_CODES[i].equals(code)) return i;
        return 0;
    }

    private String languageName(String code) {
        return LANGUAGE_NAMES[indexOfCode(code)];
    }

    private String localeTag(String code) {
        switch (code) {
            case "zh": return "zh-CN";
            case "vi": return "vi-VN";
            default: return "en-AU";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams params, int topDp) {
        params.topMargin = dp(topDp);
        return params;
    }

    private String clean(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result;
    }

    private String ellipsize(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private String formatDate(long time) {
        return new SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(new Date(time));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface SpeechResultConsumer { void accept(String text); }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_OCR_CAMERA) {
            Bitmap bitmap = data == null || data.getExtras() == null ? null : (Bitmap) data.getExtras().get("data");
            if (bitmap == null) {
                toast("The camera did not return an image.");
                return;
            }
            processOcrImage(InputImage.fromBitmap(bitmap, 0));
        } else if (requestCode == REQUEST_OCR_GALLERY && data != null && data.getData() != null) {
            try {
                processOcrImage(InputImage.fromFilePath(this, data.getData()));
            } catch (IOException e) {
                toast("The selected image could not be opened.");
            }
        } else if (requestCode == REQUEST_EXPORT_FOLDER && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            getSharedPreferences("howie_translate", MODE_PRIVATE).edit()
                    .putString("export_tree_uri", tree.toString()).apply();
            toast("Export location saved. New MP3 and MP4 files will use this folder.");
            persistentPages.remove("settings");
            showPage("settings");
        }
    }

    private void processOcrImage(InputImage image) {
        toast("Reading text from the image…");
        textRecognizer.process(image)
                .addOnSuccessListener(result -> {
                    String extracted = result.getText() == null ? "" : result.getText().trim();
                    if (extracted.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("No text detected")
                                .setMessage("Try a sharper image with larger text and better lighting.")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    if (activeOcrInput != null) activeOcrInput.setText(extracted);
                    toast("Text extracted. Review it, select the languages and tap Translate.");
                })
                .addOnFailureListener(error -> toast("OCR failed: " + (error.getMessage() == null ? "Unable to read this image." : error.getMessage())));
    }

    private void showRetranslateDialog(Models.RecordingItem item) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(20), dp(6), dp(20), 0);
        Spinner target = languageSpinner(0);
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (!LANGUAGE_CODES[i].equals(item.targetLanguage)) { target.setSelection(i); break; }
        }
        TextView preview = text("Choose a language to create a preview. The original conversation will not be changed.", 14, MUTED, false);
        panel.addView(target);
        panel.addView(preview, marginTop(matchWrap(), 12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Translate conversation")
                .setView(panel)
                .setNegativeButton("Close", null)
                .setPositiveButton("Preview", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String sourceText = removeTranscriptLabels(item.transcript == null ? "" : item.transcript);
            if (sourceText.trim().isEmpty()) sourceText = removeTranscriptLabels(item.translation == null ? "" : item.translation);
            String sourceCode = (item.transcript == null || item.transcript.trim().isEmpty()) ? item.targetLanguage : item.sourceLanguage;
            String targetCode = codeOf(target);
            preview.setText("Translating…");
            translationManager.translate(sourceCode, targetCode, sourceText, new TranslationManager.Callback() {
                @Override public void onStatus(String status) { runOnUiThread(() -> preview.setText(status)); }
                @Override public void onSuccess(String translatedText) { runOnUiThread(() -> preview.setText(translatedText)); }
                @Override public void onError(String message) { runOnUiThread(() -> preview.setText(message)); }
            });
        }));
        dialog.show();
    }

    private void showSaveHistoryDialog(Models.RecordingItem item, Runnable refresh) {
        boolean hasAudio = item.path != null && !item.path.trim().isEmpty() && new File(item.path).exists();
        String[] choices = hasAudio
                ? new String[]{"Save conversation and recording", "Save conversation only"}
                : new String[]{"Save conversation"};
        new AlertDialog.Builder(this)
                .setTitle("Save from History")
                .setItems(choices, (dialog, which) -> {
                    item.notes = item.notes == null ? "" : item.notes;
                    item.notes = item.notes.replace(HISTORY_ONLY_MARKER, "");
                    if (!item.notes.contains(SAVED_CONVERSATION_MARKER)) item.notes += " " + SAVED_CONVERSATION_MARKER;
                    if (hasAudio && which == 0) {
                        // The linked recording is retained by keeping its path on the saved record.
                    } else if (which > 0) {
                        item.path = "";
                    }
                    db.updateRecording(item);
                    toast(hasAudio && which == 0 ? "Conversation and recording saved." : "Conversation saved.");
                    persistentPages.remove("saved");
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getExportFolderLabel() {
        String value = getSharedPreferences("howie_translate", MODE_PRIVATE).getString("export_tree_uri", "");
        return value == null || value.isEmpty()
                ? "Current location: Downloads/Howie Translate"
                : "Current location: Selected Android folder\n" + value;
    }

}
