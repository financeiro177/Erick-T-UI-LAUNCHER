package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Jarvis (the voice assistant of "ai -jarvis") knows by itself. It is a different assistant from the one of
 * "ai": its own facts and its own summary of the relationship with the user. Jarvis decides what to keep;
 * the user sees and erases it with "ai -jarvis -memory".
 */
public class JarvisMemory {

    private static final String PREFS = "jarvis_memoria";
    private static final String STATE = "jarvis";
    private static final String RELATION = "relacao";

    public static final int MAX_ITEMS = 150;
    public static final int MAX_KEY = 40;
    public static final int MAX_VALUE = 300;
    public static final int MAX_RELATION = 1500;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static SharedPreferences state(Context c) {
        return c.getApplicationContext().getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    public static String normalize(String key) {
        if (key == null) return "";
        key = key.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return key.length() > MAX_KEY ? key.substring(0, MAX_KEY) : key;
    }

    public static List<String> keys(Context c) {
        List<String> keys = new ArrayList<>(prefs(c).getAll().keySet());
        Collections.sort(keys);
        return keys;
    }

    public static String get(Context c, String key) {
        return prefs(c).getString(normalize(key), null);
    }

    /** Returns null when saved, otherwise the reason it was not. */
    public static String put(Context c, String key, String value) {
        key = normalize(key);
        value = value == null ? "" : value.trim();

        if (key.length() == 0 || value.length() == 0) return "chave ou valor vazio";
        if (value.length() > MAX_VALUE) value = value.substring(0, MAX_VALUE);

        SharedPreferences p = prefs(c);
        if (!p.contains(key) && p.getAll().size() >= MAX_ITEMS) return "memória cheia (" + MAX_ITEMS + " itens); apague algo antes";

        p.edit().putString(key, value).apply();
        return null;
    }

    public static boolean remove(Context c, String key) {
        key = normalize(key);
        SharedPreferences p = prefs(c);
        if (!p.contains(key)) return false;
        p.edit().remove(key).apply();
        return true;
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
        state(c).edit().remove(RELATION).apply();
    }

    /** A few lines, written by Jarvis, about how things are between him and the user. */
    public static String relation(Context c) {
        return state(c).getString(RELATION, "");
    }

    public static void setRelation(Context c, String text) {
        text = text == null ? "" : text.trim();
        if (text.length() > MAX_RELATION) text = text.substring(0, MAX_RELATION);
        state(c).edit().putString(RELATION, text).apply();
    }
}
