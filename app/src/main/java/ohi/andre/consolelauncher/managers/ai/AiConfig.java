package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Settings of the "ai" command: API key, model, monthly limit (in dollars) and billing-cycle day.
 * Everything lives in one private SharedPreferences file (allowBackup is false in the manifest).
 * The key is never logged and never printed back (only its last 4 characters).
 */
public class AiConfig {

    private static final String PREFS = "ai_config";

    private static final String KEY = "api_key";
    private static final String MODEL = "model";
    private static final String LIMIT = "limit_usd";
    private static final String CYCLE_DAY = "cycle_day";
    private static final String WARN_TOKENS = "warn_tokens";

    // model ids are kept here (not scattered in the code) because names change over time
    public static final String HAIKU = "claude-haiku-5-5";
    public static final String SONNET = "claude-sonnet-5-5";
    public static final String OPUS = "claude-opus-5-5";

    public static final double DEFAULT_LIMIT_USD = 100;
    public static final int DEFAULT_CYCLE_DAY = 1;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- key ----

    public static boolean hasKey(Context c) {
        return key(c) != null;
    }

    /** Only for AiClient. Never print or log the result. */
    static String key(Context c) {
        String k = prefs(c).getString(KEY, null);
        return k == null || k.length() == 0 ? null : k;
    }

    public static void setKey(Context c, String key) {
        prefs(c).edit().putString(KEY, key).apply();
    }

    public static String keyHint(Context c) {
        String k = key(c);
        if (k == null) return "(sem chave)";
        return "..." + k.substring(Math.max(0, k.length() - 4));
    }

    // ---- model ----

    public static String model(Context c) {
        return prefs(c).getString(MODEL, HAIKU);
    }

    public static void setModel(Context c, String model) {
        prefs(c).edit().putString(MODEL, model).apply();
    }

    /** haiku / sonnet / opus, or a full id starting with "claude-". Returns null when unknown. */
    public static String resolveModel(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("haiku")) return HAIKU;
        if (n.equals("sonnet")) return SONNET;
        if (n.equals("opus")) return OPUS;
        if (n.startsWith("claude-") && n.length() < 80 && n.matches("[a-z0-9.\\-]+")) return n;
        return null;
    }

    /** {input, output} dollars per million tokens. Unknown models use the Sonnet price (the safe, higher estimate). */
    public static double[] prices(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("haiku")) return new double[] {0.10, 0.50};
        if (m.contains("opus")) return new double[] {4, 20};
        return new double[] {2, 10};
    }

    public static double cost(String model, long inTokens, long outTokens) {
        double[] p = prices(model);
        return (inTokens * p[0] + outTokens * p[1]) / 1000000.0;
    }

    // ---- monthly limit ----

    public static double limitUsd(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong(LIMIT, Double.doubleToRawLongBits(DEFAULT_LIMIT_USD)));
    }

    public static void setLimitUsd(Context c, double usd) {
        prefs(c).edit().putLong(LIMIT, Double.doubleToRawLongBits(usd)).apply();
    }

    /** Day of the month (1..28) when the Max plan renews. */
    public static int cycleDay(Context c) {
        int d = prefs(c).getInt(CYCLE_DAY, DEFAULT_CYCLE_DAY);
        return d < 1 || d > 28 ? DEFAULT_CYCLE_DAY : d;
    }

    public static void setCycleDay(Context c, int day) {
        prefs(c).edit().putInt(CYCLE_DAY, day).apply();
    }

    // ---- conversation size warning ----

    /** 0 = off. */
    public static int warnTokens(Context c) {
        return prefs(c).getInt(WARN_TOKENS, 0);
    }

    public static void setWarnTokens(Context c, int tokens) {
        prefs(c).edit().putInt(WARN_TOKENS, tokens).apply();
    }

    static SharedPreferences raw(Context c) {
        return prefs(c);
    }
}
