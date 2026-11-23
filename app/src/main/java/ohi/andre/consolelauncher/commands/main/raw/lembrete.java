package ohi.andre.consolelauncher.commands.main.raw;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.managers.reminder.ReminderCard;
import ohi.andre.consolelauncher.managers.reminder.ReminderReceiver;
import ohi.andre.consolelauncher.managers.reminder.ReminderScheduler;
import ohi.andre.consolelauncher.managers.reminder.ReminderStore;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * "lembrete": reminders that appear as a card on the home screen and only leave when confirmed or snoozed.
 *
 *   lembrete 18:50 ligar para a clínica               today at 18:50 (tomorrow if already past)
 *   lembrete 18:50 ligar | confirmar o horário        "|" separates the title from an optional detail
 *   lembrete +30 texto  /  +2h texto  /  +1d texto    in 30 minutes, 2 hours, 1 day
 *   lembrete amanhã 9:00 texto  /  25/12 9:00 texto   tomorrow, or a date
 *   lembrete -ls  /  -rm N  /  -teste
 *
 * Plain CommandAbstraction (like "ai") so a free sentence can follow. The "ai" command will create
 * reminders through ReminderStore.add later.
 */
public class lembrete implements CommandAbstraction {

    private static final Pattern RELATIVE = Pattern.compile("^\\+(\\d{1,4})([mhd]?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2})(?:[:h](\\d{2})|h)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})$");

    private static final String FORMATS = "Use 18:50, +30 (minutos), +2h, amanhã 9:00 ou 25/12 9:00";

    @Override
    public String exec(ExecutePack pack) {
        Context c = pack.context;
        String line = pack.getString().trim();
        if (line.length() == 0) return c.getString(helpRes());

        if (line.startsWith("-")) {
            String[] parts = line.split("\\s+", 2);
            String opt = parts[0].toLowerCase(Locale.ROOT);
            String rest = parts.length > 1 ? parts[1].trim() : "";

            switch (opt) {
                case "-ls":
                    return list(c);
                case "-rm":
                    return remove(c, rest);
                case "-teste":
                    return test(c);
                default:
                    return say(c, "lembrete: opção desconhecida (" + opt + "). Digite lembrete para ver a ajuda.");
            }
        }

        return create(c, line);
    }

    // ---- creating ----

    private static class Parsed {
        long when;
        String text = "";
        String error;
    }

    private static String[] first(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return new String[]{s.substring(0, i), s.substring(i).trim()};
    }

    /** minute of day (hour * 60 + minute) from "18:50", "18h50" or "18h"; -1 if it is not a valid time */
    private static int clock(String s) {
        Matcher m = CLOCK.matcher(s);
        if (!m.matches()) return -1;

        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return -1;
        return h * 60 + min;
    }

    private static Parsed parse(String line) {
        Parsed p = new Parsed();
        String[] t = first(line);
        Matcher m;

        if ((m = RELATIVE.matcher(t[0])).matches()) {
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            long ms = unit.equals("h") ? n * 3600000L : unit.equals("d") ? n * 86400000L : n * 60000L;
            p.when = System.currentTimeMillis() + ms;
            p.text = t[1];
            return p;
        }

        boolean tomorrow = t[0].equalsIgnoreCase("amanhã") || t[0].equalsIgnoreCase("amanha");
        boolean dated = DATE.matcher(t[0]).matches();

        if (tomorrow || dated) {
            String[] t2 = first(t[1]);
            int minutes = clock(t2[0]);
            if (minutes < 0) {
                p.error = "falta a hora depois de " + t[0] + ". " + FORMATS;
                return p;
            }

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
            cal.set(Calendar.MINUTE, minutes % 60);

            if (tomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            } else {
                Matcher d = DATE.matcher(t[0]);
                d.matches();
                int day = Integer.parseInt(d.group(1));
                int month = Integer.parseInt(d.group(2));
                if (month < 1 || month > 12 || day < 1 || day > 31) {
                    p.error = "data inválida (" + t[0] + "). " + FORMATS;
                    return p;
                }

                cal.setLenient(false);
                try {
                    cal.set(Calendar.MONTH, month - 1);
                    cal.set(Calendar.DAY_OF_MONTH, day);
                    cal.getTimeInMillis();
                } catch (Exception e) {
                    p.error = "data inválida (" + t[0] + "). " + FORMATS;
                    return p;
                }

                if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                    cal.setLenient(true);
                    cal.add(Calendar.YEAR, 1);
                }
            }

            p.when = cal.getTimeInMillis();
            p.text = t2[1];
            return p;
        }

        int minutes = clock(t[0]);
        if (minutes >= 0) {
            p.when = ReminderCard.nextOccurrence(minutes / 60, minutes % 60);
            p.text = t[1];
            return p;
        }

        p.error = "não entendi a hora (" + t[0] + "). " + FORMATS;
        return p;
    }

    private static String create(Context c, String line) {
        Parsed p = parse(line);
        if (p.error != null) return say(c, "lembrete: " + p.error);

        String title = p.text;
        String body = "";
        int bar = p.text.indexOf('|');
        if (bar >= 0) {
            title = p.text.substring(0, bar).trim();
            body = p.text.substring(bar + 1).trim();
        }
        if (title.length() == 0) return say(c, "lembrete: falta o texto. Exemplo: lembrete 18:50 ligar para a clínica");

        ReminderStore.Reminder r = ReminderStore.add(c, title, body, p.when, ReminderStore.SOURCE_USER);
        if (r == null) return say(c, "lembrete: limite de " + ReminderStore.MAX_ITEMS + " lembretes. Use lembrete -ls e lembrete -rm.");

        ReminderScheduler.schedule(c, r);
        say(c, "lembrete #" + r.id + " criado para " + ReminderStore.label(r.when));
        warnings(c);
        return null;
    }

    private static void warnings(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                warn(c, "lembrete: as notificações do T-UI estão desligadas, então o cartão só aparece com o T-UI aberto. Ligue em Configurações > Aplicativos > T-UI > Notificações.");
            }
            if (!ReminderScheduler.canBeExact(c)) {
                warn(c, "lembrete: alarme exato não liberado; o lembrete pode atrasar alguns minutos.");
            }
        } catch (Exception ignored) {}
    }

    // ---- listing, removing, testing ----

    private static String list(Context c) {
        List<ReminderStore.Reminder> all = ReminderStore.all(c);
        if (all.isEmpty()) return say(c, "lembrete: nenhum lembrete.");

        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        for (ReminderStore.Reminder r : all) {
            if (b.length() > 0) b.append('\n');
            b.append('#').append(r.id).append("  ");
            b.append(r.when <= now ? "pendente " + ReminderStore.hour(r.when) : ReminderStore.label(r.when));
            b.append("  ").append(r.title);
        }
        return say(c, b.toString());
    }

    private static String remove(Context c, String rest) {
        try {
            int id = Integer.parseInt(rest.replace("#", "").trim());
            if (!ReminderStore.remove(c, id)) return say(c, "lembrete: não existe o #" + id + ". Use lembrete -ls.");

            ReminderScheduler.cancel(c, id);
            ReminderReceiver.cancelNotification(c, id);
            return say(c, "lembrete #" + id + " removido.");
        } catch (NumberFormatException e) {
            return say(c, "lembrete: informe o número. Exemplo: lembrete -rm 3");
        }
    }

    private static String test(Context c) {
        ReminderStore.Reminder r = ReminderStore.add(c, "Teste do lembrete",
                "Se você está vendo este cartão, está tudo certo.", System.currentTimeMillis() + 8000, ReminderStore.SOURCE_USER);
        if (r == null) return say(c, "lembrete: limite de " + ReminderStore.MAX_ITEMS + " lembretes.");

        ReminderScheduler.schedule(c, r);
        say(c, "lembrete: o cartão de teste aparece em uns 8 segundos. Para testar a notificação, saia do T-UI antes.");
        warnings(c);
        return null;
    }

    private static String say(Context c, String text) {
        Tuils.sendOutput(Color.CYAN, c.getApplicationContext(), text);
        return null;
    }

    private static void warn(Context c, String text) {
        Tuils.sendOutput(Color.YELLOW, c.getApplicationContext(), text);
    }

    @Override
    public int[] argType() {
        return new int[]{CommandAbstraction.PLAIN_TEXT};
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return R.string.help_lembrete;
    }

    @Override
    public String onArgNotFound(ExecutePack pack, int index) {
        return pack.context.getString(helpRes());
    }

    @Override
    public String onNotArgEnough(ExecutePack pack, int nArgs) {
        return pack.context.getString(helpRes());
    }
}
