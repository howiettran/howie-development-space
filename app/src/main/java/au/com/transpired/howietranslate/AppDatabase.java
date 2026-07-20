package au.com.transpired.howietranslate;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class AppDatabase extends SQLiteOpenHelper {
    static final String HISTORY_ONLY_MARKER = "\u200B\u200C\u200D";
    private static final String DB_NAME = "howie_translate.db";
    private static final int DB_VERSION = 3;

    AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE glossary (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "original_text TEXT NOT NULL," +
                "translated_text TEXT NOT NULL," +
                "pinyin TEXT NOT NULL DEFAULT ''," +
                "source_language TEXT NOT NULL," +
                "target_language TEXT NOT NULL," +
                "category TEXT NOT NULL DEFAULT 'General'," +
                "notes TEXT NOT NULL DEFAULT ''," +
                "favorite INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "category TEXT NOT NULL DEFAULT 'General'," +
                "path TEXT NOT NULL," +
                "source_language TEXT NOT NULL," +
                "target_language TEXT NOT NULL," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "translation TEXT NOT NULL DEFAULT ''," +
                "pinyin TEXT NOT NULL DEFAULT ''," +
                "notes TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "sort_order INTEGER NOT NULL DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE recordings SET sort_order = id WHERE sort_order = 0");
        }
        if (oldVersion < 3) {
            // Reset the default history order to newest first. Drag-and-drop can still change the
            // stored sort_order afterwards, while every newly inserted conversation is placed at
            // the top of the list.
            Cursor cursor = db.query("recordings", new String[]{"id"}, null, null,
                    null, null, "created_at DESC, id DESC");
            db.beginTransaction();
            try {
                long order = 1L;
                while (cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put("sort_order", order++);
                    db.update("recordings", values, "id=?",
                            new String[]{String.valueOf(cursor.getLong(0))});
                }
                db.setTransactionSuccessful();
            } finally {
                cursor.close();
                db.endTransaction();
            }
        }
    }

    long insertGlossary(Models.GlossaryItem item) {
        item.createdAt = item.createdAt == 0 ? System.currentTimeMillis() : item.createdAt;
        return getWritableDatabase().insert("glossary", null, glossaryValues(item));
    }

    void updateGlossary(Models.GlossaryItem item) {
        getWritableDatabase().update("glossary", glossaryValues(item), "id=?", new String[]{String.valueOf(item.id)});
    }

    void deleteGlossary(long id) {
        getWritableDatabase().delete("glossary", "id=?", new String[]{String.valueOf(id)});
    }

    List<Models.GlossaryItem> getGlossary(String query) {
        String q = query == null ? "" : query.trim();
        String selection = null;
        String[] args = null;
        if (!q.isEmpty()) {
            selection = "original_text LIKE ? OR translated_text LIKE ? OR pinyin LIKE ? OR category LIKE ? OR notes LIKE ?";
            String like = "%" + q + "%";
            args = new String[]{like, like, like, like, like};
        }
        Cursor c = getReadableDatabase().query("glossary", null, selection, args, null, null,
                "favorite DESC, category COLLATE NOCASE, created_at DESC");
        List<Models.GlossaryItem> list = new ArrayList<>();
        try {
            while (c.moveToNext()) list.add(readGlossary(c));
        } finally {
            c.close();
        }
        return list;
    }

    long insertRecording(Models.RecordingItem item) {
        item.createdAt = item.createdAt == 0 ? System.currentTimeMillis() : item.createdAt;
        if (item.sortOrder == 0) item.sortOrder = -item.createdAt;
        return getWritableDatabase().insert("recordings", null, recordingValues(item));
    }

    void updateRecording(Models.RecordingItem item) {
        getWritableDatabase().update("recordings", recordingValues(item), "id=?", new String[]{String.valueOf(item.id)});
    }

    void deleteRecording(long id) {
        getWritableDatabase().delete("recordings", "id=?", new String[]{String.valueOf(id)});
    }

    List<Models.RecordingItem> getRecordings(String query) {
        String q = query == null ? "" : query.trim();
        String selection;
        String[] args;
        String hidden = "%" + HISTORY_ONLY_MARKER + "%";
        if (!q.isEmpty()) {
            selection = "(title LIKE ? OR category LIKE ? OR transcript LIKE ? OR translation LIKE ? OR notes LIKE ?) AND notes NOT LIKE ?";
            String like = "%" + q + "%";
            args = new String[]{like, like, like, like, like, hidden};
        } else {
            selection = "notes NOT LIKE ?";
            args = new String[]{hidden};
        }
        Cursor c = getReadableDatabase().query("recordings", null, selection, args, null, null,
                "created_at DESC, id DESC");
        List<Models.RecordingItem> list = new ArrayList<>();
        try {
            while (c.moveToNext()) list.add(readRecording(c));
        } finally {
            c.close();
        }
        return list;
    }

    List<Models.RecordingItem> getHistory(String query) {
        String q = query == null ? "" : query.trim();
        String selection = null;
        String[] args = null;
        if (!q.isEmpty()) {
            selection = "title LIKE ? OR category LIKE ? OR transcript LIKE ? OR translation LIKE ? OR notes LIKE ?";
            String like = "%" + q + "%";
            args = new String[]{like, like, like, like, like};
        }
        Cursor c = getReadableDatabase().query("recordings", null, selection, args, null, null,
                "CASE WHEN sort_order=0 THEN 1 ELSE 0 END, sort_order ASC, created_at DESC");
        List<Models.RecordingItem> list = new ArrayList<>();
        try {
            while (c.moveToNext()) list.add(readRecording(c));
        } finally {
            c.close();
        }
        return list;
    }

    void reorderRecording(long draggedId, long targetId) {
        if (draggedId == targetId) return;
        SQLiteDatabase db = getWritableDatabase();
        List<Long> ids = new ArrayList<>();
        Cursor c = db.query("recordings", new String[]{"id"}, null, null, null, null,
                "CASE WHEN sort_order=0 THEN 1 ELSE 0 END, sort_order ASC, created_at DESC");
        try {
            while (c.moveToNext()) ids.add(c.getLong(0));
        } finally {
            c.close();
        }
        if (!ids.remove(draggedId)) return;
        int targetIndex = ids.indexOf(targetId);
        if (targetIndex < 0) ids.add(draggedId); else ids.add(targetIndex, draggedId);
        db.beginTransaction();
        try {
            long order = 1;
            for (Long id : ids) {
                ContentValues values = new ContentValues();
                values.put("sort_order", order++);
                db.update("recordings", values, "id=?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("glossary", null, null);
        db.delete("recordings", null, null);
    }

    private ContentValues glossaryValues(Models.GlossaryItem item) {
        ContentValues v = new ContentValues();
        v.put("original_text", safe(item.originalText));
        v.put("translated_text", safe(item.translatedText));
        v.put("pinyin", safe(item.pinyin));
        v.put("source_language", safe(item.sourceLanguage));
        v.put("target_language", safe(item.targetLanguage));
        v.put("category", cleanCategory(item.category));
        v.put("notes", safe(item.notes));
        v.put("favorite", item.favorite ? 1 : 0);
        v.put("created_at", item.createdAt == 0 ? System.currentTimeMillis() : item.createdAt);
        return v;
    }

    private ContentValues recordingValues(Models.RecordingItem item) {
        ContentValues v = new ContentValues();
        v.put("title", safe(item.title));
        v.put("category", cleanCategory(item.category));
        v.put("path", safe(item.path));
        v.put("source_language", safe(item.sourceLanguage));
        v.put("target_language", safe(item.targetLanguage));
        v.put("transcript", safe(item.transcript));
        v.put("translation", safe(item.translation));
        v.put("pinyin", safe(item.pinyin));
        v.put("notes", safe(item.notes));
        v.put("created_at", item.createdAt == 0 ? System.currentTimeMillis() : item.createdAt);
        v.put("duration_ms", item.durationMs);
        v.put("sort_order", item.sortOrder);
        return v;
    }

    private Models.GlossaryItem readGlossary(Cursor c) {
        Models.GlossaryItem i = new Models.GlossaryItem();
        i.id = c.getLong(c.getColumnIndexOrThrow("id"));
        i.originalText = c.getString(c.getColumnIndexOrThrow("original_text"));
        i.translatedText = c.getString(c.getColumnIndexOrThrow("translated_text"));
        i.pinyin = c.getString(c.getColumnIndexOrThrow("pinyin"));
        i.sourceLanguage = c.getString(c.getColumnIndexOrThrow("source_language"));
        i.targetLanguage = c.getString(c.getColumnIndexOrThrow("target_language"));
        i.category = c.getString(c.getColumnIndexOrThrow("category"));
        i.notes = c.getString(c.getColumnIndexOrThrow("notes"));
        i.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1;
        i.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return i;
    }

    private Models.RecordingItem readRecording(Cursor c) {
        Models.RecordingItem i = new Models.RecordingItem();
        i.id = c.getLong(c.getColumnIndexOrThrow("id"));
        i.title = c.getString(c.getColumnIndexOrThrow("title"));
        i.category = c.getString(c.getColumnIndexOrThrow("category"));
        i.path = c.getString(c.getColumnIndexOrThrow("path"));
        i.sourceLanguage = c.getString(c.getColumnIndexOrThrow("source_language"));
        i.targetLanguage = c.getString(c.getColumnIndexOrThrow("target_language"));
        i.transcript = c.getString(c.getColumnIndexOrThrow("transcript"));
        i.translation = c.getString(c.getColumnIndexOrThrow("translation"));
        i.pinyin = c.getString(c.getColumnIndexOrThrow("pinyin"));
        i.notes = c.getString(c.getColumnIndexOrThrow("notes"));
        i.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        i.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
        int sortColumn = c.getColumnIndex("sort_order");
        i.sortOrder = sortColumn >= 0 ? c.getLong(sortColumn) : i.id;
        return i;
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String cleanCategory(String value) {
        String c = safe(value).trim();
        return c.isEmpty() ? "General" : c;
    }
}
