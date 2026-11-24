package ohi.andre.consolelauncher.managers.ai;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Build;

import androidx.core.app.AlarmManagerCompat;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.LauncherActivity;
import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * What makes Jarvis want to talk. A language model does not think between conversations, so "wanting to talk"
 * comes from triggers: things the phone notices (battery, charger, headphones, Wi-Fi, first use of the day,
 * days without talking, the AI budget, an update) and notes Jarvis leaves for himself during a conversation
 * ("tomorrow at nine I will ask about that").
 *
 * A trigger that fires costs nothing: it only shows "Jarvis quer falar com você", with a sound.
 * The model is called when, and only when, the user answers.
 */
public class JarvisTriggers {

    public static final String ACTION_FIRE = "ohi.andre.consolelauncher.JARVIS_TRIGGER";
    public static final String EXTRA_ID = "id";

    // kinds the phone notices by itself
    public static final String BATTERY_LOW = "bateria_baixa", BATTERY_FULL = "bateria_cheia",
            PLUGGED = "carregador_ligado", UNPLUGGED = "carregador_desligado", HEADSET = "fone_conectado",
            WIFI_LOST = "wifi_caiu", WIFI_BACK = "wifi_voltou", FIRST_OF_DAY = "primeiro_uso_do_dia",
            MISSED = "sumico", BUDGET = "gasto_ia", UPDATED = "app_atualizado", TIME = "hora";

    public static final String[] EVENT_KINDS = {BATTERY_LOW, BATTERY_FULL, PLUGGED, UNPLUGGED, HEADSET, WIFI_LOST, WIFI_BACK,
            FIRST_OF_DAY, MISSED, BUDGET, UPDATED};

    private static final String LIST = "gatilhos", OFF = "gatilhos_desligados", PENDING = "avisos",
            QUIET = "quieto_ate", LAST_NOTICE = "aviso_em", LAST_KIND = "aviso_tipo_", SNOOZE = "aviso_adiado_ate",
            DAY = "dia_visto", LAST_TALK = "conversa_em", VERSION = "versao_vista", BUDGET_CYCLE = "gasto_avisado";

    private static final long MIN_BETWEEN = 10L * 60 * 1000;          // between any two notices
    private static final long MIN_SAME_KIND = 2L * 60 * 60 * 1000;    // between two notices of the same built-in kind
    private static final long PENDING_LIFE = 12L * 60 * 60 * 1000;
    private static final int MAX_OWN = 40;

    private static final String CHANNEL = "jarvis_avisos";
    private static final int NOTIFICATION = 43002;

    private static SharedPreferences prefs(Context c) {
        return JarvisMemory.state(c);
    }

    // ---- triggers Jarvis (or the user) created ----

    public static class Trigger {
        public int id;
        public String kind = "", reason = "";
        public long when;        // only for TIME
        public boolean daily;    // TIME: every day at that hour
    }

    private static JSONArray array(Context c, String key) {
        try {
            return new JSONArray(prefs(c).getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized List<Trigger> own(Context c) {
        List<Trigger> list = new ArrayList<>();
        JSONArray a = array(c, LIST);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Trigger t = new Trigger();
            t.id = o.optInt("id");
            t.kind = o.optString("tipo");
            t.reason = o.optString("motivo");
            t.when = o.optLong("quando");
            t.daily = o.optBoolean("diario");
            list.add(t);
        }
        return list;
    }

    private static void saveOwn(Context c, List<Trigger> list) {
        JSONArray a = new JSONArray();
        try {
            for (Trigger t : list) {
                a.put(new JSONObject().put("id", t.id).put("tipo", t.kind).put("motivo", t.reason)
                        .put("quando", t.when).put("diario", t.daily));
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString(LIST, a.toString()).apply();
    }

    /**
     * kind: one of EVENT_KINDS, or TIME with "when" written as HH:mm, yyyy-MM-dd HH:mm or +minutes.
     * Returns the line to give back (to the model or to the terminal).
     */
    public static synchronized String create(Context c, String kind, String when, String reason, boolean daily) {
        kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        reason = reason == null ? "" : reason.trim();
        if (reason.length() == 0) return "erro: diga o motivo (o que você quer falar quando disparar)";
        if (reason.length() > 200) reason = reason.substring(0, 200);

        Trigger t = new Trigger();
        t.kind = kind;
        t.reason = reason;

        if (TIME.equals(kind)) {
            long at = parseWhen(when);
            if (at <= 0) return "erro: quando inválido. Use HH:mm, yyyy-MM-dd HH:mm ou +minutos (ex.: +90)";
            t.when = at;
            t.daily = daily && when != null && when.trim().matches("\\d{1,2}:\\d{2}");
        } else {
            boolean known = false;
            for (String k : EVENT_KINDS) if (k.equals(kind)) known = true;
            if (!known) return "erro: tipo desconhecido. Tipos: hora, " + join(EVENT_KINDS);
        }

        List<Trigger> list = own(c);
        if (list.size() >= MAX_OWN) return "erro: já há " + MAX_OWN + " gatilhos; remova algum antes";

        int id = 1;
        for (Trigger o : list) id = Math.max(id, o.id + 1);
        t.id = id;
        list.add(t);
        saveOwn(c, list);
        if (TIME.equals(kind)) schedule(c, t);

        return "ok, gatilho " + id + " criado: " + describe(t);
    }

    public static synchronized boolean remove(Context c, int id) {
        List<Trigger> list = own(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                cancel(c, id);
                list.remove(i);
                saveOwn(c, list);
                return true;
            }
        }
        return false;
    }

    public static String describe(Trigger t) {
        String on = TIME.equals(t.kind)
                ? (t.daily ? "todo dia às " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.when))
                : "em " + new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(t.when)))
                : "quando: " + t.kind;
        return on + " -> " + t.reason;
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) b.append(b.length() == 0 ? "" : ", ").append(s);
        return b.toString();
    }

    private static long parseWhen(String s) {
        if (s == null) return 0;
        s = s.trim();
        try {
            if (s.matches("\\+\\d{1,5}")) return System.currentTimeMillis() + Long.parseLong(s.substring(1)) * 60000L;
            if (s.matches("\\d{1,2}:\\d{2}")) {
                String[] p = s.split(":");
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
                return c.getTimeInMillis();
            }
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s);
            return d == null || d.getTime() <= System.currentTimeMillis() ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- built-in kinds: on unless the user turned one off ----

    public static boolean kindOn(Context c, String kind) {
        return !(" " + prefs(c).getString(OFF, "") + " ").contains(" " + kind + " ");
    }

    public static String setKind(Context c, String kind, boolean on) {
        kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        boolean known = false;
        for (String k : EVENT_KINDS) if (k.equals(kind)) known = true;
        if (!known) return "jarvis: tipo desconhecido. Tipos: " + join(EVENT_KINDS);

        String off = (" " + prefs(c).getString(OFF, "") + " ").replace(" " + kind + " ", " ").trim();
        if (!on) off = (off + " " + kind).trim();
        prefs(c).edit().putString(OFF, off).apply();
        return "jarvis: gatilho " + kind + (on ? " ligado." : " desligado.");
    }

    private static String builtinReason(String kind, String detail) {
        switch (kind) {
            case BATTERY_LOW: return "a bateria do celular está acabando" + detail;
            case BATTERY_FULL: return "a bateria chegou a cem por cento e o celular continua na tomada";
            case PLUGGED: return "ele acabou de pôr o celular para carregar" + detail;
            case UNPLUGGED: return "ele tirou o celular do carregador" + detail;
            case HEADSET: return "ele acabou de conectar um fone de ouvido";
            case WIFI_LOST: return "o Wi-Fi caiu";
            case WIFI_BACK: return "o Wi-Fi voltou";
            case FIRST_OF_DAY: return "é a primeira vez que ele pega o celular hoje";
            case MISSED: return "faz " + detail + " que ele não fala com você";
            case BUDGET: return "o gasto com a IA neste ciclo já passou de " + detail + " do limite";
            case UPDATED: return "o launcher acabou de ser atualizado (você pode ter ganhado ou perdido alguma coisa)";
            default: return kind;
        }
    }

    // ---- firing ----

    /** Something happened on the phone. Fires the built-in trigger of that kind and the ones Jarvis created for it. */
    public static void event(Context context, String kind, String detail) {
        final Context c = context.getApplicationContext();
        if (detail == null) detail = "";
        long now = System.currentTimeMillis();
        SharedPreferences p = prefs(c);

        List<String> reasons = new ArrayList<>();

        if (kindOn(c, kind) && now - p.getLong(LAST_KIND + kind, 0) >= MIN_SAME_KIND) {
            reasons.add(builtinReason(kind, detail));
        }

        synchronized (JarvisTriggers.class) {
            List<Trigger> list = own(c);
            boolean changed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (!kind.equals(list.get(i).kind)) continue;
                reasons.add(0, "você mesmo marcou para falar quando isto acontecesse (" + kind + "): " + list.get(i).reason);
                list.remove(i);   // an event trigger of his fires once
                changed = true;
            }
            if (changed) saveOwn(c, list);
        }

        if (reasons.isEmpty()) return;
        p.edit().putLong(LAST_KIND + kind, now).apply();
        for (String r : reasons) notice(c, r);
    }

    /** The alarm of a TIME trigger. */
    static void fired(Context c, int id) {
        Trigger hit = null;
        synchronized (JarvisTriggers.class) {
            List<Trigger> list = own(c);
            for (int i = 0; i < list.size(); i++) {
                Trigger t = list.get(i);
                if (t.id != id) continue;
                hit = t;
                if (t.daily) {
                    t.when += 24L * 60 * 60 * 1000;
                    while (t.when <= System.currentTimeMillis()) t.when += 24L * 60 * 60 * 1000;
                    schedule(c, t);
                } else {
                    list.remove(i);
                }
                saveOwn(c, list);
                break;
            }
        }
        if (hit != null) notice(c, "você mesmo marcou para falar com ele agora: " + hit.reason);
    }

    public static boolean quiet(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong(QUIET, 0);
    }

    public static String setQuiet(Context c, int minutes) {
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60000L;
        prefs(c).edit().putLong(QUIET, until).apply();
        if (minutes > 0) clearPending(c);
        return minutes <= 0 ? "ok, pode voltar a chamar" : "ok, sem avisos até " + new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(until));
    }

    private static void notice(Context c, String reason) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (quiet(c)) return;

        JSONArray pending = livePending(c);
        if (pending.length() >= 5) return;
        try {
            pending.put(new JSONObject().put("texto", reason).put("em", now));
        } catch (Exception ignored) {}
        p.edit().putString(PENDING, pending.toString()).apply();

        // several things at once are told together, with a single sound
        if (now - p.getLong(LAST_NOTICE, 0) < MIN_BETWEEN) return;
        p.edit().putLong(LAST_NOTICE, now).putLong(SNOOZE, 0).apply();

        announce(c);
    }

    private static JSONArray livePending(Context c) {
        JSONArray all = array(c, PENDING), live = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && now - o.optLong("em") < PENDING_LIFE) live.put(o);
        }
        return live;
    }

    /** What he wants to talk about, one reason per line; empty when nothing is waiting. */
    public static String pendingText(Context c) {
        JSONArray live = livePending(c);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < live.length(); i++) {
            if (b.length() > 0) b.append('\n');
            b.append(live.optJSONObject(i).optString("texto"));
        }
        return b.toString();
    }

    public static boolean hasPending(Context c) {
        return livePending(c).length() > 0;
    }

    public static boolean snoozed(Context c) {
        return System.currentTimeMillis() < prefs(c).getLong(SNOOZE, 0);
    }

    public static void snooze(Context c, int minutes) {
        prefs(c).edit().putLong(SNOOZE, System.currentTimeMillis() + minutes * 60000L).apply();
        cancelNotification(c);
    }

    public static void clearPending(Context c) {
        prefs(c).edit().remove(PENDING).apply();
        cancelNotification(c);
    }

    private static void cancelNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION);
        } catch (Exception ignored) {}
    }

    /** Shows "Jarvis quer falar com você" wherever the user is, with a sound. */
    private static void announce(final Context c) {
        // his own screen (or bubble) is on: he says it there
        JarvisSession session = JarvisSession.peek();
        if (session != null && session.announce()) {
            beep(c);
            return;
        }

        // the terminal is in front: the card appears at the top
        if (JarvisNoticeCard.show(c)) {
            beep(c);
            return;
        }

        // anywhere else: a notification, which makes the sound itself
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis quer falar", NotificationManager.IMPORTANCE_HIGH));
            }

            Intent open = new Intent(c, LauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, NOTIFICATION, open, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

            nm.notify(NOTIFICATION, new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Jarvis quer falar com você")
                    .setContentText("Toque para atender.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build());
        } catch (Exception ignored) {}
    }

    private static void beep(Context c) {
        try {
            Ringtone r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Exception ignored) {}
    }

    // ---- alarms of TIME triggers ----

    private static PendingIntent pending(Context c, int id) {
        Intent i = new Intent(c, JarvisReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, 52000 + id, i, Tuils.pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static void schedule(Context c, Trigger t) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long when = Math.max(t.when, System.currentTimeMillis() + 2000);
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pending(c, t.id));
                } else {
                    AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pending(c, t.id));
                }
            } catch (SecurityException e) {
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, when, pending(c, t.id));
            }
        } catch (Exception ignored) {}
    }

    private static void cancel(Context c, int id) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c, id));
        } catch (Exception ignored) {}
    }

    // ---- watching the phone ----

    private static boolean watching;
    private static boolean wifi = true, fullTold;

    /** Called when the launcher starts: from then on the phone's events reach the triggers. No permission is needed. */
    public static synchronized void watch(Context context) {
        if (watching) return;
        watching = true;
        final Context c = context.getApplicationContext();

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_BATTERY_LOW);
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_HEADSET_PLUG);

            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    String a = i == null ? null : i.getAction();
                    if (a == null) return;
                    try {
                        if (Intent.ACTION_BATTERY_LOW.equals(a)) {
                            event(c, BATTERY_LOW, "");
                        } else if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                            fullTold = false;
                            event(c, PLUGGED, hourDetail());
                        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                            event(c, UNPLUGGED, "");
                        } else if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                            boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                            if (plugged && !fullTold && scale > 0 && level * 100 / scale >= 100) {
                                fullTold = true;
                                event(c, BATTERY_FULL, "");
                            }
                        } else if (Intent.ACTION_HEADSET_PLUG.equals(a)) {
                            if (!isInitialStickyBroadcast() && i.getIntExtra("state", 0) == 1) event(c, HEADSET, "");
                        }
                    } catch (Exception ignored) {}
                }
            };

            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else c.registerReceiver(r, f);
        } catch (Exception ignored) {}

        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 21) {
                NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                    private boolean first = true;

                    @Override
                    public void onAvailable(Network network) {
                        if (!wifi && !first) event(c, WIFI_BACK, "");
                        wifi = true;
                        first = false;
                    }

                    @Override
                    public void onLost(Network network) {
                        if (wifi && !first) event(c, WIFI_LOST, "");
                        wifi = false;
                        first = false;
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private static String hourDetail() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 5 ? " (é tarde da noite)" : "";
    }

    /** Called every time the terminal comes to the front: the checks that need no listener. */
    public static void onLauncherResume(Context context) {
        final Context c = context.getApplicationContext();
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();

        try {
            // alarms are lost on "force stop" and on reboot
            for (Trigger t : own(c)) if (TIME.equals(t.kind)) schedule(c, t);

            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String seen = p.getString(DAY, "");
            if (!today.equals(seen)) {
                p.edit().putString(DAY, today).apply();
                if (seen.length() > 0) event(c, FIRST_OF_DAY, "");
            }

            long lastTalk = p.getLong(LAST_TALK, 0);
            if (lastTalk > 0 && now - lastTalk > 48L * 60 * 60 * 1000) {
                long days = (now - lastTalk) / (24L * 60 * 60 * 1000);
                event(c, MISSED, days + " dias");
            }

            double limit = AiConfig.limitUsd(c), spent = AiUsage.cycle(c).cost;
            String cycle = AiUsage.formatDate(AiUsage.nextRenewal(Calendar.getInstance(), AiConfig.cycleDay(c)));
            if (limit > 0 && spent >= limit * 0.5 && !cycle.equals(p.getString(BUDGET_CYCLE, ""))) {
                p.edit().putString(BUDGET_CYCLE, cycle).apply();
                event(c, BUDGET, "metade");
            }

            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long version = info.lastUpdateTime;
            long known = p.getLong(VERSION, 0);
            if (known != version) {
                p.edit().putLong(VERSION, version).apply();
                if (known != 0) event(c, UPDATED, "");
            }
        } catch (Exception ignored) {}
    }

    /** Called when the user talks to Jarvis: the "days without talking" count starts again. */
    public static void talked(Context c) {
        prefs(c).edit().putLong(LAST_TALK, System.currentTimeMillis()).apply();
    }

    // ---- for the terminal ----

    public static String list(Context c) {
        StringBuilder b = new StringBuilder("== jarvis: gatilhos ==");
        b.append("\ndo celular (ligado/desligado):");
        for (String k : EVENT_KINDS) b.append("\n  ").append(kindOn(c, k) ? "[x] " : "[ ] ").append(k);

        List<Trigger> list = own(c);
        b.append("\n\ncriados por ele (").append(list.size()).append("):");
        for (Trigger t : list) b.append("\n  ").append(t.id).append(". ").append(describe(t));
        if (list.isEmpty()) b.append("\n  (nenhum ainda; ele cria durante a conversa)");

        if (quiet(c)) {
            b.append("\n\nem silêncio até ").append(new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(prefs(c).getLong(QUIET, 0))));
        }
        String waiting = pendingText(c);
        if (waiting.length() > 0) b.append("\n\nesperando você atender:\n").append(waiting);

        b.append("\n\nDesligar um tipo: -jarvis -gatilhos -off [tipo]   Ligar: -jarvis -gatilhos -on [tipo]");
        b.append("\nApagar um dele: -jarvis -gatilhos -rm [número]   Silenciar: -jarvis -quieto [minutos] (0 volta)");
        return b.toString();
    }
}
