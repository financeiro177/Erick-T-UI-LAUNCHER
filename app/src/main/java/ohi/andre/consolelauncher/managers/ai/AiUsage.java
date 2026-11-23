package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Local count of tokens and estimated dollars, per day, taken from the "usage" field of each API reply.
 * It is an estimate: the real balance is in the Console (Billing).
 * Layout: one JSON object {"yyyy-MM-dd": {"i": inTokens, "o": outTokens, "c": dollars}}.
 */
public class AiUsage {

    private static final String DAYS = "usage_days";
    private static final String WARNED_CYCLE = "warned_cycle";
    private static final int KEEP_DAYS = 100;

    public static class Total {
        public long in, out;
        public double cost;
    }

    private static final Object LOCK = new Object();

    private static SimpleDateFormat dayFormat() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    }

    public static String dayKey(Calendar c) {
        return dayFormat().format(c.getTime());
    }

    private static JSONObject load(SharedPreferences p) {
        try {
            String s = p.getString(DAYS, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void record(Context c, String model, long inTokens, long outTokens) {
        record(c, model, inTokens, outTokens, Calendar.getInstance());
    }

    static void record(Context c, String model, long inTokens, long outTokens, Calendar now) {
        synchronized (LOCK) {
            SharedPreferences p = AiConfig.raw(c);
            JSONObject all = load(p);
            String key = dayKey(now);

            try {
                JSONObject d = all.optJSONObject(key);
                if (d == null) d = new JSONObject();
                d.put("i", d.optLong("i") + inTokens);
                d.put("o", d.optLong("o") + outTokens);
                d.put("c", d.optDouble("c", 0) + AiConfig.cost(model, inTokens, outTokens));
                all.put(key, d);

                // drop days older than KEEP_DAYS
                Calendar limit = (Calendar) now.clone();
                limit.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
                String oldest = dayKey(limit);
                List<String> remove = new ArrayList<>();
                java.util.Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.compareTo(oldest) < 0) remove.add(k);
                }
                for (String k : remove) all.remove(k);

                p.edit().putString(DAYS, all.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    /** Sum of the days from "fromInclusive" (yyyy-MM-dd) up to "toInclusive". */
    public static Total sum(Context c, String fromInclusive, String toInclusive) {
        Total t = new Total();
        synchronized (LOCK) {
            JSONObject all = load(AiConfig.raw(c));
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.compareTo(fromInclusive) < 0 || k.compareTo(toInclusive) > 0) continue;
                JSONObject d = all.optJSONObject(k);
                if (d == null) continue;
                t.in += d.optLong("i");
                t.out += d.optLong("o");
                t.cost += d.optDouble("c", 0);
            }
        }
        return t;
    }

    public static Total today(Context c) {
        String k = dayKey(Calendar.getInstance());
        return sum(c, k, k);
    }

    public static Total lastDays(Context c, int days) {
        Calendar now = Calendar.getInstance();
        Calendar from = (Calendar) now.clone();
        from.add(Calendar.DAY_OF_YEAR, -(days - 1));
        return sum(c, dayKey(from), dayKey(now));
    }

    // ---- billing cycle (follows the Max renewal day, not the calendar month) ----

    /** First day of the cycle that contains "now". cycleDay must be 1..28. */
    public static Calendar cycleStart(Calendar now, int cycleDay) {
        Calendar s = (Calendar) now.clone();
        s.set(Calendar.HOUR_OF_DAY, 0);
        s.set(Calendar.MINUTE, 0);
        s.set(Calendar.SECOND, 0);
        s.set(Calendar.MILLISECOND, 0);
        if (s.get(Calendar.DAY_OF_MONTH) < cycleDay) s.add(Calendar.MONTH, -1);
        s.set(Calendar.DAY_OF_MONTH, cycleDay);
        return s;
    }

    public static Calendar nextRenewal(Calendar now, int cycleDay) {
        Calendar s = cycleStart(now, cycleDay);
        s.add(Calendar.MONTH, 1);
        s.set(Calendar.DAY_OF_MONTH, cycleDay);
        return s;
    }

    public static Total cycle(Context c) {
        Calendar now = Calendar.getInstance();
        return sum(c, dayKey(cycleStart(now, AiConfig.cycleDay(c))), dayKey(now));
    }

    public static String formatDate(Calendar c) {
        return new SimpleDateFormat("dd/MM", Locale.US).format(c.getTime());
    }

    // ---- 80% warning, once per cycle ----

    /** True the first time it is asked in a cycle after the spending reached 80% of the limit. */
    public static boolean shouldWarnEighty(Context c) {
        synchronized (LOCK) {
            double limit = AiConfig.limitUsd(c);
            if (limit <= 0) return false;
            if (cycle(c).cost < limit * 0.8) return false;

            String id = dayKey(cycleStart(Calendar.getInstance(), AiConfig.cycleDay(c)));
            SharedPreferences p = AiConfig.raw(c);
            if (id.equals(p.getString(WARNED_CYCLE, null))) return false;
            p.edit().putString(WARNED_CYCLE, id).apply();
            return true;
        }
    }

    public static String money(double usd) {
        return String.format(Locale.US, usd >= 1 ? "US$ %.2f" : "US$ %.4f", usd);
    }
}
