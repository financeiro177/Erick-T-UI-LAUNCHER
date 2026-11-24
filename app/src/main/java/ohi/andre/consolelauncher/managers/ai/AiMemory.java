package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Facts the user agreed to keep ("contabilidade" -> "Fulana, 61 9..., WhatsApp Business").
 * They live in the app's private preferences. Only the list of keys goes with each request;
 * a value is read (by the "buscar_memoria" tool) only when the model asks for that key.
 */
public class AiMemory {

    private static final String PREFS = "ai_memoria";

    public static final int MAX_ITEMS = 50;
    public static final int MAX_KEY = 40;
    public static final int MAX_VALUE = 300;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
        if (!p.contains(key) && p.getAll().size() >= MAX_ITEMS) return "memória cheia (" + MAX_ITEMS + " itens)";

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
}
