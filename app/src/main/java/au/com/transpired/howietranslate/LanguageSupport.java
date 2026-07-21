package au.com.transpired.howietranslate;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Central language metadata shared by translation, OCR and speech features. */
final class LanguageSupport {
    static final String[] NAMES = {
            "English",
            "\u4e2d\u6587 (Mandarin)",
            "Ti\u1ebfng Vi\u1ec7t",
            "\u0e44\u0e17\u0e22",
            "Bahasa Melayu",
            "\u5ee3\u6771\u8a71 (Cantonese)",
            "\u6f6e\u5dde\u8a71 (Teo Chew)"
    };

    static final String[] CODES = {"en", "zh", "vi", "th", "ms", "yue", "nan"};

    private LanguageSupport() { }

    static String normalise(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        for (String supported : CODES) {
            if (supported.equals(value)) return supported;
        }
        return "en";
    }

    static String displayName(String code) {
        String normalised = normalise(code);
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(normalised)) return NAMES[i];
        }
        return NAMES[0];
    }

    /** ML Kit has separate Thai and Malay models, but no distinct Cantonese or Teo Chew model. */
    static String translationCode(String code) {
        switch (normalise(code)) {
            case "yue":
            case "nan":
                return "zh";
            default:
                return normalise(code);
        }
    }

    /** Whisper supports Thai and Malay; Chinese is the closest available hint for the two dialects. */
    static String whisperCode(String code) {
        switch (normalise(code)) {
            case "yue":
            case "nan":
                return "zh";
            default:
                return normalise(code);
        }
    }

    static String localeTag(String code) {
        switch (normalise(code)) {
            case "zh": return "zh-CN";
            case "vi": return "vi-VN";
            case "th": return "th-TH";
            case "ms": return "ms-MY";
            case "yue": return "yue-Hant-HK";
            // Android speech services rarely expose a Teo Chew locale. Traditional Chinese
            // recognition is the most compatible best-effort fallback.
            case "nan": return "zh-TW";
            default: return "en-AU";
        }
    }

    static boolean isChineseScript(String code) {
        String value = normalise(code);
        return "zh".equals(value) || "yue".equals(value) || "nan".equals(value);
    }

    static boolean isDialect(String code) {
        String value = normalise(code);
        return "yue".equals(value) || "nan".equals(value);
    }

    static boolean isThai(String code) {
        return "th".equals(normalise(code));
    }

    static String dialectNotice(String source, String target) {
        if ("yue".equals(normalise(target))) {
            return "Cantonese uses the Chinese translation model. Written output may be formal Chinese rather than conversational Cantonese.";
        }
        if ("nan".equals(normalise(target))) {
            return "Teo Chew uses the Chinese translation model. Dialect wording and pronunciation cannot be guaranteed.";
        }
        if ("yue".equals(normalise(source)) || "nan".equals(normalise(source))) {
            return "Dialect recognition is best effort and depends on the speech service installed on this phone.";
        }
        return "";
    }

    static String[] downloadableTranslationCodes() {
        Set<String> unique = new LinkedHashSet<>(Arrays.asList("en", "zh", "vi", "th", "ms"));
        return unique.toArray(new String[0]);
    }

    static boolean acceptsScript(String code, Character.UnicodeScript script) {
        if (script == Character.UnicodeScript.COMMON
                || script == Character.UnicodeScript.INHERITED) return true;
        if (isChineseScript(code)) {
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.LATIN;
        }
        if (isThai(code)) {
            return script == Character.UnicodeScript.THAI || script == Character.UnicodeScript.LATIN;
        }
        return script == Character.UnicodeScript.LATIN;
    }
}
