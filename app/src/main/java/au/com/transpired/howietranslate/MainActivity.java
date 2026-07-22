package au.com.transpired.howietranslate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.PlaybackParams;
import android.net.Uri;
import androidx.core.content.FileProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
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
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HISTORY_ONLY_MARKER = AppDatabase.HISTORY_ONLY_MARKER;
    private static final int REQUEST_RECORD_AUDIO = 910;
    private static final int REQUEST_SPEECH = 911;
    private static final int REQUEST_OCR_CAMERA = 912;
    private static final int REQUEST_OCR_GALLERY = 913;
    private static final int REQUEST_MP3_FOLDER = 914;
    private static final int REQUEST_MP4_FOLDER = 915;
    private static final int REQUEST_IMAGE_FOLDER = 916;
    private static final String SAVED_CONVERSATION_MARKER = "[SAVED_CONVERSATION]";
    private static final String SAVED_COPY_MARKER = "[SAVED_COPY]";
    private static final String SAVED_SOURCE_PREFIX = "[SAVED_SOURCE:";
    private Uri pendingOcrCameraUri;
    private File pendingOcrCameraFile;
    private String activeOcrImagePath = "";
    private boolean activeOcrImagePendingForHistory;
    private ImageView activeOcrPreview;
    private TextView activeOcrImageStatus;
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();

    private static final String[] LANGUAGE_NAMES = LanguageSupport.NAMES;
    private static final String[] LANGUAGE_CODES = LanguageSupport.CODES;

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
    private TextRecognizer latinTextRecognizer;
    private TextRecognizer chineseTextRecognizer;
    private EditText activeOcrInput;
    private String activeOcrSourceCode = "en";
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
    private boolean onlineRecorderActive;
    private boolean currentConversationSaved;
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
    private Runnable livePrepareWatchdog;

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
        repairAudioLinks();
        translationManager = new TranslationManager(db);
        offlineSpeechManager = new OfflineSpeechManager(this);
        streamingSpeechManager = new StreamingSpeechManager(this, offlineSpeechManager);
        googleOnlineSpeechManager = new GoogleOnlineSpeechManager(this);
        exportManager = new ExportManager(this);
        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        latinTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        chineseTextRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        setContentView(buildShell());
        showPage("translate");
    }

    @Override
    protected void onDestroy() {
        stopOnlineSidecarRecording(true);
        destroySpeechRecognizer();
        if (googleOnlineSpeechManager != null) googleOnlineSpeechManager.close();
        if (streamingSpeechManager != null) streamingSpeechManager.close();
        if (exportManager != null) exportManager.close();
        cancelLivePreparationWatchdog();
        storageExecutor.shutdownNow();
        if (offlineSpeechManager != null) offlineSpeechManager.close();
        translationManager.close();
        if (latinTextRecognizer != null) latinTextRecognizer.close();
        if (chineseTextRecognizer != null) chineseTextRecognizer.close();
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
        page.addView(sectionTitle("Translate a phrase", "Type, paste or dictate in English, Mandarin Chinese, Vietnamese, Thai, Malay, Cantonese or Teo Chew."));

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

        LinearLayout imagePreviewCard = cardTint(Color.rgb(247, 250, 255));
        activeOcrPreview = new ImageView(this);
        activeOcrPreview.setAdjustViewBounds(true);
        activeOcrPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        activeOcrPreview.setVisibility(View.GONE);
        activeOcrImageStatus = text("No image attached to the next translation.", 13, MUTED, false);
        LinearLayout imagePreviewActions = horizontal();
        Button viewOcrImage = button("View original", false);
        Button removeOcrImage = button("Remove image", false);
        imagePreviewActions.addView(viewOcrImage, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams removeImageLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        removeImageLp.setMargins(dp(7), 0, 0, 0);
        imagePreviewActions.addView(removeOcrImage, removeImageLp);
        imagePreviewCard.addView(activeOcrPreview,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        imagePreviewCard.addView(activeOcrImageStatus, marginTop(matchWrap(), 8));
        imagePreviewCard.addView(imagePreviewActions, marginTop(matchWrap(), 8));
        page.addView(imagePreviewCard, marginTop(matchWrap(), 8));
        viewOcrImage.setOnClickListener(v -> showImageViewer(activeOcrImagePath));
        removeOcrImage.setOnClickListener(v -> clearOcrImageSelection());

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
            clearOcrImageSelection();
        });
        listen.setOnClickListener(v -> startSpeechInput(codeOf(source), recognised -> {
            input.setText(recognised);
            doTranslation(codeOf(source), codeOf(target), recognised, result, pinyin, status, progress);
        }));
        captureImage.setOnClickListener(v -> {
            activeOcrInput = input;
            activeOcrSourceCode = codeOf(source);
            try {
                File cameraDir = new File(getCacheDir(), "ocr_camera");
                if (!cameraDir.exists()) cameraDir.mkdirs();
                File imageFile = File.createTempFile("howie_ocr_", ".jpg", cameraDir);
                pendingOcrCameraFile = imageFile;
                pendingOcrCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingOcrCameraUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_OCR_CAMERA);
            } catch (ActivityNotFoundException e) {
                toast("No camera app is available.");
            } catch (Exception e) {
                toast("The camera could not be opened: " + (e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        });
        uploadImage.setOnClickListener(v -> {
            activeOcrInput = input;
            activeOcrSourceCode = codeOf(source);
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
            item.pinyin = LanguageSupport.isChineseScript(item.targetLanguage) ? PinyinUtil.toPinyin(translatedText)
                    : (LanguageSupport.isChineseScript(item.sourceLanguage) ? PinyinUtil.toPinyin(original) : "");
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
        String[] engineOptions = {
                "Google Online – best recognition; audio depends on phone",
                "Offline Whisper – recommended when audio must be saved"
        };
        ArrayAdapter<String> engineAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, engineOptions);
        conversationEngine.setAdapter(engineAdapter);
        // Use Offline Whisper by default because it records a guaranteed local audio file.
        // Google Online remains available for users who prefer its recognition quality.
        conversationEngine.setSelection(1);
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

        saveRecordingButton = button(currentConversationSaved ? "Saved ✓" : "Save to Saved", true);
        saveRecordingButton.setEnabled(!currentConversationSaved && !isRecording && hasCurrentConversationContent());
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
            String chinese = LanguageSupport.isChineseScript(item.targetLanguage) ? translated
                    : (LanguageSupport.isChineseScript(item.sourceLanguage) ? original : "");
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
            repairAnyAudioIfPossible(item);
            if (!item.category.equalsIgnoreCase(lastCategory == null ? "" : lastCategory)) {
                TextView heading = text(item.category, 15, DARK_BLUE, true);
                list.addView(heading, marginTop(matchWrap(), lastCategory == null ? 0 : 14));
                lastCategory = item.category;
            }
            LinearLayout card = card();
            TextView title = text(item.title, 18, TEXT, true);
            boolean savedHasAudio = item.path != null && !item.path.trim().isEmpty()
                    && new File(item.path).isFile() && new File(item.path).length() > 1024L;
            boolean savedHasImage = validImagePath(item.imagePath);
            TextView meta = text(languageName(item.sourceLanguage) + " → " + languageName(item.targetLanguage)
                    + "  •  " + formatDuration(item.durationMs) + "  •  " + formatDate(item.createdAt)
                    + "  •  " + (savedHasAudio ? "Audio available" : "Transcript only")
                    + (savedHasImage ? "  •  Original image" : ""), 12, MUTED, false);
            TextView preview = text(ellipsize(item.transcript.isEmpty() ? "No subtitle added" : ensureTranscriptNumbering(item.transcript), 150), 14, TEXT, false);
            card.addView(title);
            card.addView(meta, marginTop(matchWrap(), 4));
            card.addView(preview, marginTop(matchWrap(), 8));
            addOriginalImageToContainer(card, item);

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
            mp3.setEnabled(savedHasAudio);
            karaoke.setEnabled(savedHasAudio);
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
            repairHistoryAudioIfPossible(item, item.id == currentHistoryId ? currentRecordingFile : null);
            LinearLayout recordCard = card();
            LinearLayout top = horizontal();
            TextView title = text(chronologicalNumbers.get(item.id) + ".  " + item.title, 18, TEXT, true);
            Button drag = button("☰ Drag", false);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(drag, new LinearLayout.LayoutParams(dp(92), dp(42)));
            recordCard.addView(top);
            boolean historyHasAudio = item.path != null && !item.path.trim().isEmpty()
                    && new File(item.path).isFile() && new File(item.path).length() > 1024L;
            boolean historyHasImage = validImagePath(item.imagePath);
            recordCard.addView(text(item.category + "  •  " + languageName(item.sourceLanguage) + " → "
                    + languageName(item.targetLanguage) + "  •  " + formatDuration(item.durationMs)
                    + "  •  " + (historyHasAudio ? "Audio available" : "Transcript only")
                    + (historyHasImage ? "  •  Original image" : ""), 12, MUTED, false), marginTop(matchWrap(), 4));
            recordCard.addView(text(formatDate(item.createdAt), 12, MUTED, false), marginTop(matchWrap(), 2));
            String previewText = item.transcript == null || item.transcript.trim().isEmpty()
                    ? "No original transcript" : ensureTranscriptNumbering(item.transcript);
            recordCard.addView(text(ellipsize(previewText, 190), 14, TEXT, false), marginTop(matchWrap(), 8));
            addOriginalImageToContainer(recordCard, item);

            LinearLayout actions = horizontal();
            Button open = button("Open", true);
            Button retranslate = button("Translate", false);
            Models.RecordingItem savedCopy = db.getSavedCopyForSource(item.id);
            boolean alreadySaved = savedCopy != null;
            boolean savedNeedsAudioRepair = alreadySaved && historyHasAudio && !validAudioPath(savedCopy.path);
            Button saveItem = button(savedNeedsAudioRepair ? "Repair Saved Audio"
                    : (alreadySaved ? "Saved ✓" : "Save"), false);
            saveItem.setEnabled(!alreadySaved || savedNeedsAudioRepair);
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
        modelCard.addView(text("Prepare English, Mandarin Chinese, Vietnamese, Thai and Malay once. Cantonese and Teo Chew share the Chinese writing model. The app now prepares each language independently so one failed download cannot hold up every other model.", 14, MUTED, false), marginTop(matchWrap(), 6));
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
        speechCard.addView(buildSpeechModelRow("th"), marginTop(matchWrap(), 8));
        speechCard.addView(buildSpeechModelRow("ms"), marginTop(matchWrap(), 8));
        speechCard.addView(buildSpeechModelRow("yue"), marginTop(matchWrap(), 8));
        speechCard.addView(buildSpeechModelRow("nan"), marginTop(matchWrap(), 8));

        LinearLayout testRow = horizontal();
        Spinner testLanguage = languageSpinner(1);
        Button testSpeech = button("Test Google Online", false);
        testRow.addView(testLanguage, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(0, dp(50), 1.15f);
        testLp.setMargins(dp(8), 0, 0, 0);
        testRow.addView(testSpeech, testLp);
        speechCard.addView(testRow, marginTop(matchWrap(), 12));
        page.addView(speechCard, marginTop(matchWrap(), 10));

        LinearLayout exportCard = card();
        exportCard.addView(text("MP3, MP4 and image save locations", 19, TEXT, true));
        exportCard.addView(text("Choose separate Android folders for audio, video and original OCR images. Protected app copies remain linked to History and Saved even if the external folder changes.", 14, MUTED, false), marginTop(matchWrap(), 6));
        TextView mp3FolderLabel = text(getExportFolderLabel("mp3_export_tree_uri", "MP3"), 13, DARK_BLUE, false);
        TextView mp4FolderLabel = text(getExportFolderLabel("mp4_export_tree_uri", "MP4"), 13, DARK_BLUE, false);
        TextView imageFolderLabel = text(getExportFolderLabel("image_export_tree_uri", "Image"), 13, DARK_BLUE, false);
        Button chooseMp3Folder = button("Choose MP3 folder", false);
        Button chooseMp4Folder = button("Choose MP4 folder", false);
        Button chooseImageFolder = button("Choose image folder", false);
        exportCard.addView(mp3FolderLabel, marginTop(matchWrap(), 10));
        exportCard.addView(chooseMp3Folder, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 7));
        exportCard.addView(mp4FolderLabel, marginTop(matchWrap(), 12));
        exportCard.addView(chooseMp4Folder, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 7));
        exportCard.addView(imageFolderLabel, marginTop(matchWrap(), 12));
        exportCard.addView(chooseImageFolder, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)), 7));
        page.addView(exportCard, marginTop(matchWrap(), 10));

        chooseMp3Folder.setOnClickListener(v -> openExportFolderPicker(REQUEST_MP3_FOLDER));
        chooseMp4Folder.setOnClickListener(v -> openExportFolderPicker(REQUEST_MP4_FOLDER));
        chooseImageFolder.setOnClickListener(v -> openExportFolderPicker(REQUEST_IMAGE_FOLDER));

        LinearLayout storageCard = card();
        storageCard.addView(text("Storage and uninstall", 19, TEXT, true));
        storageCard.addView(text("The app is a standard Android installation. It does not use device-administrator access. Android can uninstall it normally. Uninstalling removes its private database, recordings, translation packs and the bundled Whisper model.", 14, MUTED, false), marginTop(matchWrap(), 6));
        Button appInfo = button("Open App Info / Uninstall", false);
        storageCard.addView(appInfo, marginTop(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)), 10));
        page.addView(storageCard, marginTop(matchWrap(), 10));

        LinearLayout about = cardTint(Color.rgb(255, 244, 246));
        about.addView(text("Howie Translate 0.10.2 live-start and model-download fix build", 17, RED, true));
        about.addView(text("Starts listening and recording without waiting for translation downloads, and prepares all translation languages independently so the Settings button cannot remain stuck on the first model.", 14, TEXT, false), marginTop(matchWrap(), 6));
        page.addView(about, marginTop(matchWrap(), 10));

        download.setOnClickListener(v -> {
            download.setEnabled(false);
            modelStatus.setText("Starting downloads…");
            translationManager.prepareAll(new TranslationManager.PreparationCallback() {
                @Override public void onProgress(String message) { runOnUiThread(() -> modelStatus.setText(message)); }
                @Override public void onComplete() { runOnUiThread(() -> {
                    modelStatus.setText("English pairs for Chinese, Vietnamese, Thai and Malay are ready ✓");
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
        String statusText = LanguageSupport.isDialect(language)
                ? "Best effort: uses the multilingual Whisper Chinese speech model"
                : "Included in the multilingual Whisper model ✓";
        TextView status = text(statusText, 13,
                LanguageSupport.isDialect(language) ? DARK_BLUE : Color.rgb(25, 125, 75), false);
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
            status.setText("The bundled multilingual Whisper model is ready ✓");
            status.setTextColor(Color.rgb(25, 125, 75));
            button.setEnabled(true);
            button.setText("All speech models installed ✓");
            toast("The multilingual Whisper speech model is ready.");
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
        status.setText("Checking the selected translation model…");
        translationManager.translate(source, target, input, new TranslationManager.Callback() {
            @Override public void onStatus(String message) { runOnUiThread(() -> status.setText(message)); }
            @Override public void onSuccess(String translatedText) { runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                result.setText(translatedText);
                updatePinyin(pinyin, source, input, target, translatedText);
                autoSaveTypedHistory(source, target, input, translatedText);
                String dialectNotice = LanguageSupport.dialectNotice(source, target);
                status.setText(dialectNotice.isEmpty()
                        ? "Translated and saved to History."
                        : "Translated and saved to History.\n" + dialectNotice);
            }); }
            @Override public void onError(String message) { runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                status.setText(message);
                toast("Translation could not be completed.");
            }); }
        });
    }

    private void updatePinyin(TextView view, String source, String sourceText, String target, String targetText) {
        String chinese = LanguageSupport.isChineseScript(target) ? targetText
                : (LanguageSupport.isChineseScript(source) ? sourceText : "");
        view.setText(PinyinUtil.toPinyin(chinese));
        view.setVisibility(chinese.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private boolean startOnlineSidecarRecording(File outputFile) {
        stopOnlineSidecarRecording(false);
        if (outputFile == null) return false;
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            onlineRecorderActive = true;
            return true;
        } catch (Exception firstFailure) {
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) { }
            }
            recorder = null;
            onlineRecorderActive = false;
            if (outputFile.exists()) outputFile.delete();
            return false;
        }
    }

    private File stopOnlineSidecarRecording(boolean keepValidFile) {
        MediaRecorder active = recorder;
        boolean wasActive = onlineRecorderActive;
        recorder = null;
        onlineRecorderActive = false;
        if (active != null) {
            if (wasActive) {
                try { active.stop(); } catch (RuntimeException ignored) { }
            }
            try { active.reset(); } catch (Exception ignored) { }
            try { active.release(); } catch (Exception ignored) { }
        }
        File file = currentRecordingFile;
        boolean valid = file != null && file.isFile() && file.length() > 1024L;
        if (!keepValidFile || !valid) {
            if (file != null && file.isFile() && active != null) file.delete();
            return null;
        }
        return file;
    }

    private boolean hasCurrentConversationContent() {
        boolean hasAudio = currentRecordingFile != null && currentRecordingFile.isFile()
                && currentRecordingFile.length() > 0L;
        boolean hasTranscript = conversationTranscript != null
                && !conversationTranscript.getText().toString().trim().isEmpty();
        boolean hasTranslation = conversationTranslation != null
                && !conversationTranslation.getText().toString().trim().isEmpty();
        return hasAudio || hasTranscript || hasTranslation;
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
            // Listening and recording must never wait for a translation model download. Start the
            // microphone first, then warm the selected pair in the background. Final phrases are
            // queued safely until the pair becomes ready.
            beginLiveConversation(source, target, token);
            warmLiveTranslationPair(source, target, token);
        } else {
            ensureLiveSpeechModels(source, target, () -> {
                if (token == liveSessionToken && liveControlState == LiveControlState.PREPARING) {
                    beginLiveConversation(source, target, token);
                    warmLiveTranslationPair(source, target, token);
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

    private void warmLiveTranslationPair(String source, String target, long token) {
        translationManager.preparePair(source, target, new TranslationManager.PreparationCallback() {
            @Override public void onProgress(String message) {
                runOnUiThread(() -> {
                    if (token == liveSessionToken && liveControlState == LiveControlState.RECORDING
                            && conversationLiveTranslation != null
                            && conversationTranslation.getText().toString().trim().isEmpty()) {
                        conversationLiveTranslation.setText("Translation setup: " + message);
                    }
                });
            }

            @Override public void onComplete() {
                runOnUiThread(() -> {
                    if (token == liveSessionToken && liveControlState == LiveControlState.RECORDING
                            && conversationLiveTranslation != null
                            && conversationTranslation.getText().toString().trim().isEmpty()) {
                        conversationLiveTranslation.setText("Translation ready • waiting for the first phrase…");
                    }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (token != liveSessionToken || liveControlState == LiveControlState.IDLE) return;
                    if (conversationLiveTranslation != null) {
                        conversationLiveTranslation.setText("Listening is active. Translation setup needs attention: " + message);
                    }
                    if (conversationStatus != null) {
                        conversationStatus.setText("Listening and recording continue. Connect to the internet and retry translation if captions remain unavailable.");
                    }
                });
            }
        });
    }

    private void beginLiveConversation(String source, String target, long token) {
        if (token != liveSessionToken || liveControlState != LiveControlState.PREPARING) return;
        cancelLivePreparationWatchdog();
        if (currentRecordingFile != null && currentRecordingFile.exists() && currentHistoryId == 0) currentRecordingFile.delete();
        currentHistoryId = 0;
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            resetLiveControlsToIdle("The recording folder could not be created.");
            toast("The recording folder could not be created.");
            return;
        }
        currentRecordingFile = new File(dir, "conversation_" + System.currentTimeMillis()
                + (activeGoogleOnlineEngine ? ".m4a" : ".wav"));
        currentConversationSaved = false;
        if (activeGoogleOnlineEngine && !startOnlineSidecarRecording(currentRecordingFile)) {
            // Some phones do not allow the app and the online recogniser to capture the
            // microphone at the same time. Continue with the transcript, but make the limitation
            // visible instead of creating a database link to an empty or corrupt file.
            currentRecordingFile = null;
            if (conversationStatus != null) {
                conversationStatus.setText("Google Online is ready, but this phone would not allow a separate audio recording. Choose Offline Whisper when the audio file must be retained.");
            }
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
                conversationLivePinyin.setText(LanguageSupport.isChineseScript(language)
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
                conversationLivePinyin.setText(LanguageSupport.isChineseScript(language)
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
                File completedAudio = activeGoogleOnlineEngine
                        ? stopOnlineSidecarRecording(true) : audioFile;
                finishLiveRecordingUi(durationMs, completedAudio);
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
            String previewTarget = otherLiveLanguage(language);
            if (!partial.isEmpty() && !partial.equals(lastPreviewSource) && !liveTranslationBusy
                    && translationManager.isPairReady(language, previewTarget)) {
                lastPreviewSource = partial;
                int generation = partial.hashCode();
                String target = previewTarget;
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
                    String chinese = LanguageSupport.isChineseScript(segment.target) ? translatedText
                            : (LanguageSupport.isChineseScript(segment.source) ? segment.text : "");
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
        if (!liveAudioStopped) return;
        // Saving must not depend on translation completion. Earlier builds left this button
        // disabled whenever a queued translation stalled, even though the audio had already
        // finished and was safe to retain.
        autoSaveLiveHistory();
        boolean hasAudio = validAudioFile(currentRecordingFile);
        boolean ready = hasCurrentConversationContent() && !currentConversationSaved;
        if (saveRecordingButton != null) {
            saveRecordingButton.setEnabled(ready);
            saveRecordingButton.setText(currentConversationSaved ? "Saved ✓" : "Save to Saved");
        }
        if (conversationStatus != null) {
            boolean translationsPending = liveTranslationBusy || !liveTranslationQueue.isEmpty();
            if (currentConversationSaved) {
                conversationStatus.setText("Saved. History and Saved each keep a durable audio link.");
            } else if (hasAudio && translationsPending) {
                conversationStatus.setText("Audio is safely stored in History. You can save it now while the last translation finishes.");
            } else if (hasAudio) {
                conversationStatus.setText("Saved automatically to History. Tap Save to Saved to create a separate protected copy with audio.");
            } else {
                conversationStatus.setText("Saved automatically to History without reusable audio. Choose Offline Whisper when the recording must be retained.");
            }
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
            File completedAudio = activeGoogleOnlineEngine
                    ? stopOnlineSidecarRecording(true) : currentRecordingFile;
            finishLiveRecordingUi(elapsed, completedAudio);
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
        if (validAudioFile(audioFile)) {
            currentRecordingFile = audioFile;
        } else if (!validAudioFile(currentRecordingFile)) {
            currentRecordingFile = null;
        }
        if (validAudioFile(currentRecordingFile) && currentHistoryId > 0) {
            try {
                currentRecordingFile = promoteAudioToHistoryStorage(currentRecordingFile, currentHistoryId);
            } catch (IOException e) {
                // Keep the original valid file rather than losing the recording because the
                // promotion copy failed. persistLiveHistory() will retain this path.
                if (conversationStatus != null) {
                    conversationStatus.setText("The recording was retained, but could not be moved into protected History storage yet.");
                }
            }
        }
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

    private void armLivePreparationWatchdog(long token) {
        cancelLivePreparationWatchdog();
        livePrepareWatchdog = () -> {
            if (token != liveSessionToken || liveControlState != LiveControlState.PREPARING) return;
            resetLiveControlsToIdle("Start Live could not finish preparing the translation model.");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Start Live preparation stopped")
                    .setMessage("The translation model did not become ready. Check the internet connection and Google Play Services, then try again. The Start Live button has been reset so the app is not left stuck in Preparing mode.")
                    .setPositiveButton("OK", null)
                    .show();
        };
        // Slightly longer than TranslationManager's model timeout, so its more specific error is
        // normally shown first. This is a final UI safety net if an Android task never returns.
        liveControlHandler.postDelayed(livePrepareWatchdog, 190000L);
    }

    private void cancelLivePreparationWatchdog() {
        if (livePrepareWatchdog != null) {
            liveControlHandler.removeCallbacks(livePrepareWatchdog);
            livePrepareWatchdog = null;
        }
    }

    private void resetLiveControlsToIdle(String message) {
        cancelLivePreparationWatchdog();
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
        if (isRecording || liveControlState == LiveControlState.RECORDING
                || liveControlState == LiveControlState.STOPPING) {
            toast("Stop the live conversation before saving it.");
            return;
        }
        if (!hasCurrentConversationContent()) {
            toast("There is no conversation to save yet.");
            return;
        }
        persistLiveHistory(currentHistoryId == 0);
        Models.RecordingItem historyItem = findCurrentHistoryItem();
        if (historyItem == null) {
            toast("The History conversation could not be found. Please start a new conversation and try again.");
            return;
        }
        historyItem = repairHistoryAudioIfPossible(historyItem, currentRecordingFile);
        Models.RecordingItem saved = createSavedCopy(historyItem, true);
        if (saved == null) return;
        currentConversationSaved = true;
        if (recordingTimer != null) recordingTimer.setText("Saved  " + formatDuration(historyItem.durationMs));
        if (conversationStatus != null) conversationStatus.setText(validAudioPath(saved.path)
                ? "Conversation and audio saved. History and Saved now have separate protected audio files."
                : "Conversation saved, but this session did not contain a reusable audio recording.");
        if (saveRecordingButton != null) {
            saveRecordingButton.setText("Saved ✓");
            saveRecordingButton.setEnabled(false);
        }
        persistentPages.remove("saved");
        toast(validAudioPath(saved.path)
                ? "Conversation and audio saved."
                : "Conversation saved without audio.");
    }

    private Models.RecordingItem createSavedCopy(Models.RecordingItem source, boolean showErrors) {
        if (source == null) return null;
        source = repairHistoryAudioIfPossible(source,
                source.id == currentHistoryId ? currentRecordingFile : null);
        File sourceAudio = resolveAudioFile(source);

        Models.RecordingItem existingSaved = db.getSavedCopyForSource(source.id);
        if (existingSaved != null) {
            // Repair an older transcript-only Saved row when its History audio or image still exists.
            try {
                if (!validAudioPath(existingSaved.path) && sourceAudio != null) {
                    existingSaved.path = copyAudioToSavedStorage(sourceAudio, source.id);
                    existingSaved.durationMs = Math.max(existingSaved.durationMs, source.durationMs);
                }
                if (!validImagePath(existingSaved.imagePath) && validImagePath(source.imagePath)) {
                    existingSaved.imagePath = copyImageToSavedStorage(new File(source.imagePath), source.id);
                }
                db.updateRecording(existingSaved);
            } catch (IOException e) {
                if (showErrors) showAudioSaveError(e);
                return null;
            }
            return existingSaved;
        }

        Models.RecordingItem saved = new Models.RecordingItem();
        saved.title = source.title;
        saved.category = source.category;
        saved.sourceLanguage = source.sourceLanguage;
        saved.targetLanguage = source.targetLanguage;
        saved.transcript = source.transcript;
        saved.translation = source.translation;
        saved.pinyin = source.pinyin;
        saved.durationMs = source.durationMs;
        saved.createdAt = System.currentTimeMillis();
        saved.sortOrder = 0;
        saved.notes = cleanSavedNotes(source.notes, source.id);
        if (validImagePath(source.imagePath)) {
            try {
                saved.imagePath = copyImageToSavedStorage(new File(source.imagePath), source.id);
            } catch (IOException e) {
                if (showErrors) showAudioSaveError(e);
                return null;
            }
        } else {
            saved.imagePath = "";
        }

        if (sourceAudio != null) {
            try {
                saved.path = copyAudioToSavedStorage(sourceAudio, source.id);
            } catch (IOException e) {
                if (showErrors) showAudioSaveError(e);
                return null;
            }
        } else {
            saved.path = "";
        }
        saved.id = db.insertRecording(saved);
        if (saved.id <= 0) {
            if (validAudioPath(saved.path)) new File(saved.path).delete();
            if (validImagePath(saved.imagePath)) new File(saved.imagePath).delete();
            if (showErrors) toast("The conversation could not be added to Saved.");
            return null;
        }
        return saved;
    }

    private void showAudioSaveError(IOException e) {
        new AlertDialog.Builder(this)
                .setTitle("Media could not be saved")
                .setMessage("The History item is still safe, but its audio or original image could not be copied to Saved. "
                        + (e.getMessage() == null ? "Please try again." : e.getMessage()))
                .setPositiveButton("OK", null)
                .show();
    }

    private boolean validAudioFile(File file) {
        return file != null && file.isFile() && file.length() > 1024L;
    }

    private boolean validAudioPath(String path) {
        return path != null && !path.trim().isEmpty() && validAudioFile(new File(path));
    }

    private File resolveAudioFile(Models.RecordingItem item) {
        return item != null && validAudioPath(item.path) ? new File(item.path) : null;
    }

    private Models.RecordingItem repairHistoryAudioIfPossible(Models.RecordingItem item, File preferred) {
        if (item == null || (item.notes != null && item.notes.contains(SAVED_COPY_MARKER))) return item;
        if (validAudioPath(item.path)) return item;
        // Typed translations have no audio. Do not accidentally attach an unrelated nearby file.
        if (!validAudioFile(preferred) && item.durationMs <= 1000L) return item;
        File candidate = validAudioFile(preferred) ? preferred : findHistoryAudioCandidate(item);
        if (!validAudioFile(candidate)) return item;
        try {
            File protectedAudio = promoteAudioToHistoryStorage(candidate, item.id);
            item.path = protectedAudio.getAbsolutePath();
            db.updateRecording(item);
            if (item.id == currentHistoryId) currentRecordingFile = protectedAudio;
        } catch (IOException ignored) {
            // Keep the candidate path when it is already a valid app-private recording. This is
            // still safer than leaving the database blank and losing playback/export access.
            item.path = candidate.getAbsolutePath();
            db.updateRecording(item);
            if (item.id == currentHistoryId) currentRecordingFile = candidate;
        }
        return item;
    }

    private Models.RecordingItem repairAnyAudioIfPossible(Models.RecordingItem item) {
        if (item == null) return null;
        if (item.notes != null && item.notes.contains(SAVED_COPY_MARKER)) {
            long sourceId = savedSourceId(item.notes);
            Models.RecordingItem source = sourceId > 0 ? db.getRecording(sourceId) : null;
            source = repairHistoryAudioIfPossible(source,
                    source != null && source.id == currentHistoryId ? currentRecordingFile : null);
            boolean changed = false;
            if (!validAudioPath(item.path)) {
                File sourceAudio = resolveAudioFile(source);
                if (sourceAudio != null) {
                    try {
                        item.path = copyAudioToSavedStorage(sourceAudio, sourceId);
                        changed = true;
                    } catch (IOException ignored) { }
                }
            }
            if (!validImagePath(item.imagePath) && source != null && validImagePath(source.imagePath)) {
                try {
                    item.imagePath = copyImageToSavedStorage(new File(source.imagePath), sourceId);
                    changed = true;
                } catch (IOException ignored) { }
            }
            if (changed) db.updateRecording(item);
            return item;
        }
        if (!validAudioPath(item.path)) {
            return repairHistoryAudioIfPossible(item,
                    item.id == currentHistoryId ? currentRecordingFile : null);
        }
        return item;
    }

    private void repairAudioLinks() {
        List<Models.RecordingItem> items = db.getAllRecordings();
        for (Models.RecordingItem item : items) {
            if (item.notes == null || !item.notes.contains(SAVED_COPY_MARKER)) {
                repairHistoryAudioIfPossible(item, null);
            }
        }
        // A failed v0.9.1 save may already have created a Saved transcript row without audio.
        // Reattach it automatically from the repaired History source when possible.
        items = db.getAllRecordings();
        for (Models.RecordingItem item : items) {
            if (item.notes != null && item.notes.contains(SAVED_COPY_MARKER)
                    && (!validAudioPath(item.path) || !validImagePath(item.imagePath))) {
                repairAnyAudioIfPossible(item);
            }
        }
    }

    private File findHistoryAudioCandidate(Models.RecordingItem item) {
        if (item == null) return null;
        File historyDir = new File(getFilesDir(), "history_recordings");
        File exact = newestValidFileWithPrefix(historyDir, "history_" + item.id + "_");
        if (exact != null) return exact;

        File recordingsDir = new File(getFilesDir(), "recordings");
        File[] files = recordingsDir.listFiles();
        if (files == null) return null;
        File best = null;
        long bestDifference = Long.MAX_VALUE;
        Set<String> pathsUsedByOtherRows = new HashSet<>();
        for (Models.RecordingItem other : db.getAllRecordings()) {
            if (other.id != item.id && validAudioPath(other.path)) {
                pathsUsedByOtherRows.add(new File(other.path).getAbsolutePath());
            }
        }
        for (File file : files) {
            if (!validAudioFile(file) || pathsUsedByOtherRows.contains(file.getAbsolutePath())) continue;
            long timestamp = timestampFromRecordingName(file.getName());
            if (timestamp <= 0) timestamp = file.lastModified();
            long difference = Math.abs(timestamp - item.createdAt);
            if (difference < bestDifference && difference <= 30L * 60L * 1000L) {
                bestDifference = difference;
                best = file;
            }
        }
        return best;
    }

    private File newestValidFileWithPrefix(File directory, String prefix) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        File newest = null;
        for (File file : files) {
            if (!file.getName().startsWith(prefix) || !validAudioFile(file)) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    private long timestampFromRecordingName(String name) {
        if (name == null || !name.startsWith("conversation_")) return -1L;
        int start = "conversation_".length();
        int end = name.indexOf('.', start);
        if (end < 0) end = name.length();
        try {
            return Long.parseLong(name.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private long savedSourceId(String notes) {
        if (notes == null) return -1L;
        int start = notes.indexOf(SAVED_SOURCE_PREFIX);
        if (start < 0) return -1L;
        start += SAVED_SOURCE_PREFIX.length();
        int end = notes.indexOf(']', start);
        if (end <= start) return -1L;
        try {
            return Long.parseLong(notes.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private File promoteAudioToHistoryStorage(File source, long historyId) throws IOException {
        if (!validAudioFile(source)) throw new IOException("The recorded audio file is incomplete.");
        File dir = new File(getFilesDir(), "history_recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("The protected History audio folder could not be created.");
        }
        if (source.getParentFile() != null && source.getParentFile().equals(dir)
                && source.getName().startsWith("history_" + historyId + "_")) {
            return source;
        }
        String extension = extensionOf(source.getName());
        File destination = new File(dir, "history_" + historyId + "_"
                + System.currentTimeMillis() + extension);
        copyFileVerified(source, destination);
        return destination;
    }

    private String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private void copyFileVerified(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("The destination folder could not be created.");
        }
        File temporary = new File(destination.getAbsolutePath() + ".part");
        if (temporary.exists()) temporary.delete();
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            try { output.getFD().sync(); } catch (Exception ignored) { }
        }
        if (!temporary.isFile() || temporary.length() != source.length() || temporary.length() <= 1024L) {
            temporary.delete();
            throw new IOException("The copied audio file was incomplete.");
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("The previous destination file could not be replaced.");
        }
        if (!temporary.renameTo(destination)) {
            try (FileInputStream input = new FileInputStream(temporary);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
                try { output.getFD().sync(); } catch (Exception ignored) { }
            }
            temporary.delete();
        }
        if (!validAudioFile(destination) || destination.length() != source.length()) {
            destination.delete();
            throw new IOException("The protected audio copy could not be verified.");
        }
    }

    private String cleanSavedNotes(String notes, long sourceId) {
        String clean = notes == null ? "" : notes
                .replace(HISTORY_ONLY_MARKER, "")
                .replace(SAVED_COPY_MARKER, "")
                .replace(SAVED_CONVERSATION_MARKER, "")
                .replaceAll("\\[SAVED_SOURCE:[0-9]+\\]", "")
                .trim();
        return (clean + " " + SAVED_CONVERSATION_MARKER + " " + SAVED_COPY_MARKER
                + " " + SAVED_SOURCE_PREFIX + sourceId + "]").trim();
    }

    private String copyAudioToSavedStorage(File source, long sourceId) throws IOException {
        if (!validAudioFile(source)) throw new IOException("The source recording is missing or incomplete.");
        File dir = new File(getFilesDir(), "saved_recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("The Saved audio folder could not be created.");
        }
        File destination = new File(dir, "saved_" + sourceId + "_"
                + System.currentTimeMillis() + extensionOf(source.getName()));
        copyFileVerified(source, destination);
        return destination.getAbsolutePath();
    }

    private String copyImageToSavedStorage(File source, long sourceId) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0L) {
            throw new IOException("The source image is missing or incomplete.");
        }
        File dir = new File(getFilesDir(), "saved_images");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("The Saved image folder could not be created.");
        }
        File destination = new File(dir, "saved_image_" + sourceId + "_"
                + System.currentTimeMillis() + imageExtension(source.getName()));
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copyStream(input, output);
        }
        if (!destination.isFile() || destination.length() != source.length()) {
            destination.delete();
            throw new IOException("The Saved image copy could not be verified.");
        }
        return destination.getAbsolutePath();
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
        stopOnlineSidecarRecording(false);
        currentRecordingFile = null;
        currentHistoryId = 0;
        currentConversationSaved = false;
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
        if (saveRecordingButton != null) {
            saveRecordingButton.setText("Save to Saved");
            saveRecordingButton.setEnabled(false);
        }
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
        item.title = (activeOcrImagePendingForHistory ? "Image translation " : "Typed translation ")
                + formatDate(System.currentTimeMillis());
        item.category = activeOcrImagePendingForHistory ? "Image Translations" : "Typed Translations";
        item.path = "";
        item.imagePath = activeOcrImagePendingForHistory && validImagePath(activeOcrImagePath)
                ? activeOcrImagePath : "";
        item.sourceLanguage = source;
        item.targetLanguage = target;
        item.transcript = "1. [00:00] " + languageName(source) + ": " + original.trim();
        item.translation = "1. [00:00] " + languageName(target) + ": " + translated.trim();
        String chinese = LanguageSupport.isChineseScript(target) ? translated
                : (LanguageSupport.isChineseScript(source) ? original : "");
        item.pinyin = chinese.isEmpty() ? "" : "1. [00:00] " + PinyinUtil.toPinyin(chinese);
        item.notes = HISTORY_ONLY_MARKER;
        long inserted = db.insertRecording(item);
        if (inserted > 0 && validImagePath(item.imagePath)) {
            activeOcrImagePendingForHistory = false;
            updateOcrImagePreview("Original image retained in History and in the selected image folder.");
        }
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
        // Never replace a working History audio path with an empty path. The old code did this
        // during late callbacks and page changes, which made Open, MP3 and MP4 stop working.
        if (validAudioFile(currentRecordingFile)) {
            item.path = currentRecordingFile.getAbsolutePath();
        } else if (!validAudioPath(item.path)) {
            item.path = "";
        }
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
            item.id = currentHistoryId;
        } else {
            db.updateRecording(item);
            currentHistoryId = item.id;
        }
        // If the user saved while the last translation was still finishing, keep the Saved copy's
        // text in sync without touching its independent protected audio path.
        Models.RecordingItem savedCopy = db.getSavedCopyForSource(currentHistoryId);
        if (savedCopy != null) {
            savedCopy.title = item.title;
            savedCopy.category = item.category;
            savedCopy.sourceLanguage = item.sourceLanguage;
            savedCopy.targetLanguage = item.targetLanguage;
            savedCopy.transcript = item.transcript;
            savedCopy.translation = item.translation;
            savedCopy.pinyin = item.pinyin;
            savedCopy.durationMs = item.durationMs;
            db.updateRecording(savedCopy);
        }
    }

    private Models.RecordingItem findCurrentHistoryItem() {
        return currentHistoryId == 0 ? null : db.getRecording(currentHistoryId);
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
        addOriginalImageToContainer(body, item);
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
        final Models.RecordingItem playerItem = repairAnyAudioIfPossible(item);
        File file = playerItem.path == null || playerItem.path.trim().isEmpty()
                ? null : new File(playerItem.path);
        if (file == null || !file.isFile() || file.length() <= 1024L) {
            String message;
            if (playerItem.path == null || playerItem.path.trim().isEmpty()) {
                message = "No reusable audio file was created for this conversation. This commonly happens when Google Online owns the microphone on the phone. Use Offline Whisper for conversations where the recording must always be retained.";
            } else {
                message = "The linked audio file is missing or incomplete. The transcript is still available, but this recording cannot be played.";
            }
            new AlertDialog.Builder(this)
                    .setTitle("Audio unavailable")
                    .setMessage(message)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("View transcript", (dialog, which) -> showTextHistory(playerItem))
                    .show();
            return;
        }
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(10), dp(18), 0);
        TextView original = text(playerItem.transcript.isEmpty() ? "No original subtitle has been added." : ensureTranscriptNumbering(playerItem.transcript), 18, TEXT, true);
        TextView py = text(ensureTranscriptNumbering(playerItem.pinyin), 15, RED, false);
        py.setTypeface(Typeface.create("sans", Typeface.ITALIC));
        TextView translated = text(playerItem.translation.isEmpty() ? "No translated subtitle has been added." : ensureTranscriptNumbering(playerItem.translation), 16, TEXT, false);
        body.addView(text("ORIGINAL SUBTITLE", 11, BLUE, true));
        body.addView(original, marginTop(matchWrap(), 5));
        if (!playerItem.pinyin.isEmpty()) body.addView(py, marginTop(matchWrap(), 5));
        body.addView(text("TRANSLATION", 11, RED, true), marginTop(matchWrap(), 14));
        body.addView(translated, marginTop(matchWrap(), 5));
        addOriginalImageToContainer(body, playerItem);

        SeekBar seek = new SeekBar(this);
        TextView time = text("00:00 / " + formatDuration(playerItem.durationMs), 13, MUTED, false);
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
                .setTitle(playerItem.title)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        MediaPlayer player = new MediaPlayer();
        Handler handler = new Handler(Looper.getMainLooper());
        final float[] playbackSpeed = {1f};
        final Runnable[] updater = new Runnable[1];
        try {
            player.setDataSource(playerItem.path);
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
        mp3.setOnClickListener(v -> exportMp3(playerItem));
        karaoke.setOnClickListener(v -> exportKaraokeMp4(playerItem));
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
        final Models.RecordingItem exportItem = repairAnyAudioIfPossible(item);
        if (!validAudioPath(exportItem.path)) {
            toast("The audio recording is missing, so this file cannot be exported.");
            return;
        }
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
        try {
            if (karaoke) {
                exportManager.exportKaraokeMp4(exportItem, callback);
            } else {
                exportManager.exportMp3(exportItem, callback);
            }
        } catch (Throwable failure) {
            dialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("Export could not start")
                    .setMessage("The export service could not be started safely. Reopen the app and try again. "
                            + (failure.getMessage() == null ? "" : failure.getMessage()))
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showExportComplete(Uri uri, String displayName, String mimeType) {
        new AlertDialog.Builder(this)
                .setTitle("Export saved")
                .setMessage(displayName + " was saved successfully to the folder selected in Settings.")
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
                    item.pinyin = LanguageSupport.isChineseScript(item.targetLanguage) ? PinyinUtil.toPinyin(item.translation)
                            : (LanguageSupport.isChineseScript(item.sourceLanguage) ? PinyinUtil.toPinyin(item.transcript) : "");
                    item.notes = notes.getText().toString().trim();
                    db.updateRecording(item);
                    afterSave.run();
                }).show();
    }

    private void confirmDeleteRecording(Models.RecordingItem item, Runnable afterDelete) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this item?")
                .setMessage("This removes this History or Saved item. Shared audio and image files are only deleted when no other record still uses them.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String path = item.path == null ? "" : item.path.trim();
                    String imagePath = item.imagePath == null ? "" : item.imagePath.trim();
                    boolean shared = db.hasOtherRecordingUsingPath(path, item.id);
                    boolean imageShared = db.hasOtherRecordingUsingImagePath(imagePath, item.id);
                    db.deleteRecording(item.id);
                    if (!shared && !path.isEmpty()) {
                        File f = new File(path);
                        if (f.isFile()) f.delete();
                    }
                    if (!imageShared && !imagePath.isEmpty()) {
                        File image = new File(imagePath);
                        if (image.isFile()) image.delete();
                    }
                    persistentPages.remove("history");
                    persistentPages.remove("saved");
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
                    String chinese = LanguageSupport.isChineseScript(item.targetLanguage) ? item.translatedText
                            : (LanguageSupport.isChineseScript(item.sourceLanguage) ? item.originalText : "");
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
        String normalised = LanguageSupport.normalise(code);
        for (int i = 0; i < LANGUAGE_CODES.length; i++) if (LANGUAGE_CODES[i].equals(normalised)) return i;
        return 0;
    }

    private String languageName(String code) {
        return LanguageSupport.displayName(code);
    }

    private String localeTag(String code) {
        return LanguageSupport.localeTag(code);
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
            if (pendingOcrCameraUri == null || pendingOcrCameraFile == null
                    || !pendingOcrCameraFile.isFile()) {
                toast("The camera image could not be located.");
                return;
            }
            processOcrSource(pendingOcrCameraUri, pendingOcrCameraFile.getName());
        } else if (requestCode == REQUEST_OCR_GALLERY && data != null && data.getData() != null) {
            Uri selected = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selected,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            processOcrSource(selected, queryDisplayName(selected));
        } else if ((requestCode == REQUEST_MP3_FOLDER || requestCode == REQUEST_MP4_FOLDER
                || requestCode == REQUEST_IMAGE_FOLDER) && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            String key;
            String type;
            if (requestCode == REQUEST_MP3_FOLDER) {
                key = "mp3_export_tree_uri";
                type = "MP3";
            } else if (requestCode == REQUEST_MP4_FOLDER) {
                key = "mp4_export_tree_uri";
                type = "MP4";
            } else {
                key = "image_export_tree_uri";
                type = "Image";
            }
            getSharedPreferences("howie_translate", MODE_PRIVATE).edit()
                    .putString(key, tree.toString()).apply();
            toast(type + " save location updated.");
            persistentPages.remove("settings");
            showPage("settings");
        }
    }

    private File retainOriginalImage(Uri sourceUri, String suggestedName) throws IOException {
        File directory = new File(getFilesDir(), "history_images");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("The protected image folder could not be created.");
        }
        String extension = imageExtension(suggestedName);
        File destination = new File(directory, "image_" + System.currentTimeMillis() + extension);
        try (InputStream input = getContentResolver().openInputStream(sourceUri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Android could not open the selected image.");
            copyStream(input, output);
        } catch (Exception e) {
            destination.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage() == null ? "The image copy failed." : e.getMessage(), e);
        }
        if (!destination.isFile() || destination.length() <= 0L) {
            destination.delete();
            throw new IOException("The retained image was empty.");
        }
        return destination;
    }

    private void saveImageToChosenFolder(File source, String displayName) {
        try {
            Uri savedUri = copyImageToUserFolder(source, displayName);
            runOnUiThread(() -> {
                if (activeOcrImageStatus != null && source.getAbsolutePath().equals(activeOcrImagePath)) {
                    activeOcrImageStatus.setText("Original image retained and copied to the image folder. It will be linked to History after translation.");
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (activeOcrImageStatus != null && source.getAbsolutePath().equals(activeOcrImagePath)) {
                    activeOcrImageStatus.setText("Original image retained inside the app. External image-folder copy failed: "
                            + (e.getMessage() == null ? "storage unavailable" : e.getMessage()));
                }
            });
        }
    }

    private Uri copyImageToUserFolder(File source, String displayName) throws IOException {
        ContentResolver resolver = getContentResolver();
        String selectedTree = getSharedPreferences("howie_translate", MODE_PRIVATE)
                .getString("image_export_tree_uri", "");
        String safeName = safeImageName(displayName);
        String mimeType = imageMimeType(safeName);
        if (selectedTree != null && !selectedTree.trim().isEmpty()) {
            Uri treeUri = Uri.parse(selectedTree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            Uri destination = DocumentsContract.createDocument(resolver, parent, mimeType, safeName);
            if (destination == null) throw new IOException("The selected image folder is unavailable.");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("The selected image folder could not be opened.");
                copyStream(input, output);
            }
            return destination;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Howie Translate/Translation Images");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Android could not create the image file.");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Android could not open the image file.");
                copyStream(input, output);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException(e.getMessage(), e);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            return uri;
        }
        File root = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (root == null) throw new IOException("Pictures storage is unavailable.");
        File folder = new File(root, "Howie Translate/Translation Images");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("The image folder could not be created.");
        File destination = uniqueImageFile(folder, safeName);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copyStream(input, output);
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", destination);
    }

    private File uniqueImageFile(File folder, String displayName) {
        File candidate = new File(folder, displayName);
        if (!candidate.exists()) return candidate;
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String extension = dot > 0 ? displayName.substring(dot) : "";
        int number = 2;
        while (candidate.exists()) candidate = new File(folder, base + " (" + number++ + ")" + extension);
        return candidate;
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        output.flush();
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return "translation-image.jpg";
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) return value;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "translation-image.jpg";
    }

    private String imageExtension(String name) {
        if (name == null) return ".jpg";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".heic")) return ".heic";
        if (lower.endsWith(".heif")) return ".heif";
        return ".jpg";
    }

    private String safeImageName(String name) {
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) base = "Howie-Translate-Image-" + System.currentTimeMillis() + ".jpg";
        base = base.replaceAll("[\\\\/:*?\"<>|]", "-").replaceAll("\\s+", " ").trim();
        if (!base.contains(".")) base += ".jpg";
        return base.length() > 100 ? base.substring(base.length() - 100) : base;
    }

    private String imageMimeType(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private void updateOcrImagePreview(String message) {
        if (activeOcrPreview != null) {
            Bitmap bitmap = decodeSampledBitmap(activeOcrImagePath, 900, 500);
            activeOcrPreview.setImageBitmap(bitmap);
            activeOcrPreview.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        }
        if (activeOcrImageStatus != null) activeOcrImageStatus.setText(message);
    }

    private void clearOcrImageSelection() {
        if (activeOcrImagePendingForHistory && validImagePath(activeOcrImagePath)) {
            new File(activeOcrImagePath).delete();
        }
        activeOcrImagePath = "";
        activeOcrImagePendingForHistory = false;
        if (activeOcrPreview != null) {
            activeOcrPreview.setImageDrawable(null);
            activeOcrPreview.setVisibility(View.GONE);
        }
        if (activeOcrImageStatus != null) {
            activeOcrImageStatus.setText("No image attached to the next translation.");
        }
    }

    private boolean validImagePath(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path);
        return file.isFile() && file.length() > 0L;
    }

    private Bitmap decodeSampledBitmap(String path, int requiredWidth, int requiredHeight) {
        if (!validImagePath(path)) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > requiredWidth * 2
                    || bounds.outHeight / sample > requiredHeight * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void showImageViewer(String path) {
        if (!validImagePath(path)) {
            toast("The original image is unavailable.");
            return;
        }
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(decodeSampledBitmap(path, 1600, 1600));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(image);
        new AlertDialog.Builder(this)
                .setTitle("Original translation image")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setNeutralButton("Share", (dialog, which) -> shareImage(path))
                .setPositiveButton("Open full size", (dialog, which) -> openImage(path))
                .show();
    }

    private void openImage(String path) {
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, imageMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            toast("No app is available to open this image.");
        }
    }

    private void shareImage(String path) {
        try {
            File file = new File(path);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(imageMimeType(file.getName()));
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share original image"));
        } catch (Exception e) {
            toast("The original image could not be shared.");
        }
    }

    private void addOriginalImageToContainer(LinearLayout container, Models.RecordingItem item) {
        if (container == null || item == null || !validImagePath(item.imagePath)) return;
        container.addView(text("ORIGINAL IMAGE", 11, BLUE, true), marginTop(matchWrap(), 12));
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageBitmap(decodeSampledBitmap(item.imagePath, 900, 520));
        preview.setOnClickListener(v -> showImageViewer(item.imagePath));
        container.addView(preview, marginTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)), 6));
        Button view = button("View / share original image", false);
        view.setOnClickListener(v -> showImageViewer(item.imagePath));
        container.addView(view, marginTop(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)), 7));
    }

    private void processOcrSource(Uri sourceUri, String suggestedName) {
        if (sourceUri == null) {
            toast("The selected image could not be opened.");
            return;
        }
        if (activeOcrImageStatus != null) activeOcrImageStatus.setText("Retaining the original image…");
        storageExecutor.execute(() -> {
            try {
                File retained = retainOriginalImage(sourceUri, suggestedName);
                runOnUiThread(() -> {
                    activeOcrImagePath = retained.getAbsolutePath();
                    activeOcrImagePendingForHistory = true;
                    updateOcrImagePreview("Original image retained. It will be linked to History after translation.");
                    try {
                        processOcrImage(InputImage.fromFilePath(this, Uri.fromFile(retained)));
                    } catch (IOException e) {
                        toast("The retained image could not be opened for OCR.");
                    }
                });
                saveImageToChosenFolder(retained, retained.getName());
            } catch (Exception e) {
                runOnUiThread(() -> {
                    clearOcrImageSelection();
                    toast("The original image could not be retained: "
                            + (e.getMessage() == null ? "Unknown storage error" : e.getMessage()));
                });
            }
        });
    }

    private void processOcrImage(InputImage image) {
        String sourceCode = LanguageSupport.normalise(activeOcrSourceCode);
        TextRecognizer recognizer = LanguageSupport.isChineseScript(sourceCode)
                ? chineseTextRecognizer : latinTextRecognizer;
        toast("Reading the full-resolution image as " + languageName(sourceCode) + "...");
        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    String extracted = extractOcrText(result, sourceCode);
                    if (extracted.isEmpty()) {
                        String extra = LanguageSupport.isThai(sourceCode)
                                ? " Thai-script OCR is not available in the current ML Kit recognizer. You can copy Thai text from Google Lens and paste it into Translate."
                                : "";
                        new AlertDialog.Builder(this)
                                .setTitle("No text detected")
                                .setMessage("Try cropping closer to the text, hold the camera square to the page, and use brighter even lighting." + extra)
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    showOcrReview(extracted, sourceCode);
                })
                .addOnFailureListener(error -> toast("OCR failed: "
                        + (error.getMessage() == null ? "Unable to read this image." : error.getMessage())));
    }

    private String extractOcrText(Text result, String sourceCode) {
        if (result == null) return "";
        if (!LanguageSupport.isChineseScript(sourceCode) && !LanguageSupport.isThai(sourceCode)) {
            return result.getText() == null ? "" : result.getText().trim();
        }

        Character.UnicodeScript expected = LanguageSupport.isThai(sourceCode)
                ? Character.UnicodeScript.THAI : Character.UnicodeScript.HAN;
        StringBuilder filtered = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText() == null ? "" : line.getText().trim();
                if (!value.isEmpty() && containsScript(value, expected)) {
                    if (filtered.length() > 0) filtered.append('\n');
                    filtered.append(value);
                }
            }
        }
        return filtered.toString().trim();
    }

    private boolean containsScript(String value, Character.UnicodeScript expected) {
        if (value == null) return false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == expected) return true;
        }
        return false;
    }

    private void showOcrReview(String extracted, String sourceCode) {
        EditText review = input("Review detected text...", 8);
        review.setText(extracted);
        review.setSelection(review.getText().length());
        String message = "Only text matching the selected source language was kept. Edit any OCR mistakes before translating.";
        if (LanguageSupport.isDialect(sourceCode)) {
            message += " Cantonese and Teo Chew images use the Chinese-script recognizer.";
        }
        LinearLayout body = vertical();
        body.addView(text(message, 13, MUTED, false));
        body.addView(review, marginTop(matchWrap(), 10));
        new AlertDialog.Builder(this)
                .setTitle("Review detected " + languageName(sourceCode) + " text")
                .setView(wrapDialog(body))
                .setNegativeButton("Discard", null)
                .setPositiveButton("Use text", (dialog, which) -> {
                    if (activeOcrInput != null) activeOcrInput.setText(review.getText().toString().trim());
                    toast("Detected text added. Tap Translate when ready.");
                })
                .show();
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
        item = repairHistoryAudioIfPossible(item,
                item.id == currentHistoryId ? currentRecordingFile : null);
        Models.RecordingItem existingSaved = db.getSavedCopyForSource(item.id);
        boolean hasAudio = validAudioPath(item.path);
        boolean hasImage = validImagePath(item.imagePath);
        boolean existingHasAudio = existingSaved != null && validAudioPath(existingSaved.path);
        boolean existingHasImage = existingSaved != null && validImagePath(existingSaved.imagePath);
        boolean audioComplete = !hasAudio || existingHasAudio;
        boolean imageComplete = !hasImage || existingHasImage;
        if (existingSaved != null && audioComplete && imageComplete) {
            toast("This conversation and its available media are already in Saved.");
            return;
        }
        String title = existingSaved == null ? "Save conversation" : "Repair Saved media";
        String message;
        if (existingSaved != null) {
            message = "The Saved transcript exists, but one or more linked media files are missing. Fresh protected copies will be created from History where available.";
        } else if (hasAudio && hasImage) {
            message = "Separate protected copies of the conversation, audio and original image will be created. History will remain untouched.";
        } else if (hasAudio) {
            message = "A separate copy of the conversation and audio will be created. The original History audio will remain untouched.";
        } else if (hasImage) {
            message = "A separate copy of the conversation and original image will be created. The History image will remain untouched.";
        } else {
            message = "This conversation has no reusable audio or image file. Its transcript and translation can still be saved.";
        }
        final Models.RecordingItem sourceItem = item;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(existingSaved == null ? "Save" : "Repair", (dialog, which) -> {
                    Models.RecordingItem saved = createSavedCopy(sourceItem, true);
                    if (saved == null) return;
                    String savedMessage = validAudioPath(saved.path)
                            ? "Conversation and audio are available in Saved."
                            : "Conversation saved without audio.";
                    if (validImagePath(saved.imagePath)) savedMessage += " Original image retained.";
                    toast(savedMessage);
                    persistentPages.remove("saved");
                    persistentPages.remove("history");
                    refresh.run();
                })
                .show();
    }

    private void openExportFolderPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private String getExportFolderLabel(String preferenceKey, String type) {
        String value = getSharedPreferences("howie_translate", MODE_PRIVATE).getString(preferenceKey, "");
        return value == null || value.isEmpty()
                ? type + " location: Downloads/Howie Translate"
                : type + " location: Selected Android folder\n" + value;
    }


}