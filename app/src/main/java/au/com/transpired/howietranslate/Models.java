package au.com.transpired.howietranslate;

final class Models {
    private Models() {}

    static final class GlossaryItem {
        long id;
        String originalText = "";
        String translatedText = "";
        String pinyin = "";
        String sourceLanguage = "en";
        String targetLanguage = "zh";
        String category = "General";
        String notes = "";
        boolean favorite;
        long createdAt;
    }

    static final class RecordingItem {
        long id;
        String title = "";
        String category = "General";
        String path = "";
        String sourceLanguage = "en";
        String targetLanguage = "zh";
        String transcript = "";
        String translation = "";
        String pinyin = "";
        String notes = "";
        long createdAt;
        long durationMs;
        long sortOrder;
    }
}
