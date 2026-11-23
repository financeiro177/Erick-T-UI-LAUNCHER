package ohi.andre.consolelauncher.managers.reminder;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reminders saved in the app's private preferences (they survive a reboot). A reminder stays here until
 * the user confirms it ("Li, pode fechar") or removes it; snoozing only moves its time.
 * The "lembrete" command and, later, the "ai" command both create reminders through add().
 */
public class ReminderStore {

    public static class Reminder {
        public int id;
        public String title, body, source;
        public long when, created;
    }

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AI = "ai";

    private static final String PREFS = "reminders";
    private static final String LIST = "list";
    private static final String NEXT = "next_id";

    public static final int MAX_ITEMS = 100;
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 400;

    private static final Object LOCK = new Object();

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<Reminder> load(Context c) {
        List<Reminder> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Reminder r = new Reminder();
                r.id = o.getInt("id");
                r.title = o.optString("title", "");
                r.body = o.optString("body", "");
                r.source = o.optString("source", SOURCE_USER);
                r.when = o.getLong("when");
                r.created = o.optLong("created", r.when);
                out.add(r);
            }
        } catch (Exception ignored) {}

        Collections.sort(out, new Comparator<Reminder>() {
            @Override
            public int compare(Reminder a, Reminder b) {
                return a.when < b.when ? -1 : (a.when == b.when ? 0 : 1);
            }
        });
        return out;
    }

    private static void save(Context c, List<Reminder> list) {
        try {
            JSONArray a = new JSONArray();
            for (Reminder r : list) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("title", r.title);
                o.put("body", r.body);
                o.put("source", r.source);
                o.put("when", r.when);
                o.put("created", r.created);
                a.put(o);
            }
            prefs(c).edit().putString(LIST, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static List<Reminder> all(Context c) {
        synchronized (LOCK) {
            return load(c);
        }
    }

    public static Reminder get(Context c, int id) {
        synchronized (LOCK) {
            for (Reminder r : load(c)) if (r.id == id) return r;
            return null;
        }
    }

    /** Reminders whose time has come, oldest first. */
    public static List<Reminder> due(Context c, long now) {
        List<Reminder> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Reminder r : load(c)) if (r.when <= now) out.add(r);
        }
        return out;
    }

    /** Returns null when the limit of reminders was reached. */
    public static Reminder add(Context c, String title, String body, long when, String source) {
        synchronized (LOCK) {
            List<Reminder> list = load(c);
            if (list.size() >= MAX_ITEMS) return null;

            int id = prefs(c).getInt(NEXT, 1);
            prefs(c).edit().putInt(NEXT, id + 1).apply();

            Reminder r = new Reminder();
            r.id = id;
            r.title = cut(title, MAX_TITLE);
            r.body = cut(body, MAX_BODY);
            r.source = SOURCE_AI.equals(source) ? SOURCE_AI : SOURCE_USER;
            r.when = when;
            r.created = System.currentTimeMillis();
            list.add(r);
            save(c, list);
            return r;
        }
    }

    public static boolean remove(Context c, int id) {
        synchronized (LOCK) {
            List<Reminder> list = load(c);
            boolean found = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).id == id) {
                    list.remove(i);
                    found = true;
                }
            }
            if (found) save(c, list);
            return found;
        }
    }

    /** Moves the reminder to a new time (snooze). */
    public static Reminder snooze(Context c, int id, long when) {
        synchronized (LOCK) {
            List<Reminder> list = load(c);
            for (Reminder r : list) {
                if (r.id == id) {
                    r.when = when;
                    save(c, list);
                    return r;
                }
            }
            return null;
        }
    }

    public static String hour(long when) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(when);
    }

    /** "hoje 18:50", "amanhã 09:00" or "25/12 09:00". */
    public static String label(long when) {
        Calendar now = Calendar.getInstance();
        Calendar w = Calendar.getInstance();
        w.setTimeInMillis(when);

        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);

        if (sameDay(now, w)) return "hoje " + hour(when);
        if (sameDay(tomorrow, w)) return "amanhã " + hour(when);
        return new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(when);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
