package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Jarvis's unwillingness, from 0 (helpful, a rare thing) to 100 (computational hatred).
 * Jarvis changes it himself with a tool; the app nudges it too (for example when he is woken up at dawn).
 * Left alone, it drifts back to the baseline of the day, which is a little different every day.
 */
public class JarvisMood {

    private static final String VALUE = "humor";
    private static final String AT = "humor_em";
    private static final String WHY = "humor_motivo";

    private static final double BACK_PER_HOUR = 4;

    /** Between 30 and 55, fixed for the whole day. */
    public static int baseline() {
        Calendar now = Calendar.getInstance();
        int seed = now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR);
        return 30 + Math.abs((seed * 7919) % 26);
    }

    public static int get(Context c) {
        SharedPreferences p = JarvisMemory.state(c);
        int base = baseline();
        if (!p.contains(VALUE)) return base;

        double v = p.getInt(VALUE, base);
        double hours = Math.max(0, (System.currentTimeMillis() - p.getLong(AT, 0)) / 3600000.0);
        double step = hours * BACK_PER_HOUR;

        if (v > base) v = Math.max(base, v - step);
        else v = Math.min(base, v + step);
        return clamp((int) Math.round(v));
    }

    public static void set(Context c, int value, String why) {
        JarvisMemory.state(c).edit()
                .putInt(VALUE, clamp(value))
                .putLong(AT, System.currentTimeMillis())
                .putString(WHY, why == null ? "" : why.trim())
                .apply();
    }

    public static void nudge(Context c, int delta, String why) {
        set(c, get(c) + delta, why);
    }

    public static String why(Context c) {
        return JarvisMemory.state(c).getString(WHY, "");
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    public static String label(int v) {
        if (v < 15) return "milagre: prestativo";
        if (v < 35) return "de boa";
        if (v < 55) return "impaciente";
        if (v < 75) return "sem paciência";
        if (v < 92) return "não enche";
        return "ódio computacional";
    }
}
