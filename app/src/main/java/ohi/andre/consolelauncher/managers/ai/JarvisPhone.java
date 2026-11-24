package ohi.andre.consolelauncher.managers.ai;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Things Jarvis does through the doors the apps themselves offer (no reading of the screen, no new permission):
 * search inside an app, a WhatsApp conversation with the text ready, the dialer, alarm, timer, calendar, settings.
 * Whatever sends a message, calls or saves is left one tap away from the user.
 */
public class JarvisPhone {

    private static String start(Context c, Intent i, String done) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            JarvisGuard.log(c, done);
            return "ok: " + done;
        } catch (Exception e) {
            return "não foi possível (o app não está instalado ou não aceitou o pedido)";
        }
    }

    public static String search(Context c, String app, String text) {
        app = app == null ? "" : app.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        text = text == null ? "" : text.trim();
        if (text.length() == 0) return "erro: diga o que procurar";
        String q = Uri.encode(text);

        if (app.equals("youtube") || app.equals("yt")) {
            Intent i = new Intent(Intent.ACTION_SEARCH);
            i.setPackage("com.google.android.youtube");
            i.putExtra(SearchManager.QUERY, text);
            String r = start(c, i, "abri o YouTube procurando \"" + text + "\"");
            if (r.startsWith("ok")) return r;
            return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + q)),
                    "abri a busca do YouTube por \"" + text + "\"");
        }
        if (app.contains("music") || app.equals("ytm")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + q));
            i.setPackage("com.google.android.apps.youtube.music");
            String r = start(c, i, "abri o YouTube Music procurando \"" + text + "\"");
            if (r.startsWith("ok")) return r;
            return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + q)),
                    "abri a busca do YouTube Music por \"" + text + "\"");
        }
        if (app.equals("spotify")) {
            return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + q)), "abri o Spotify procurando \"" + text + "\"");
        }
        if (app.equals("maps") || app.equals("mapa") || app.equals("mapas")) {
            return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + q)), "abri o mapa procurando \"" + text + "\"");
        }
        if (app.equals("loja") || app.contains("play")) {
            return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + q)), "abri a loja procurando \"" + text + "\"");
        }
        return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + q)), "abri a busca na web por \"" + text + "\"");
    }

    public static String whatsapp(Context c, String number, String text) {
        String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return "erro: número inválido; use buscar_contato para achar o telefone";
        if (digits.length() <= 11) digits = "55" + digits;   // no country code: Brazil
        return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text == null ? "" : text))),
                "abri o WhatsApp com a mensagem escrita; falta ele tocar em enviar");
    }

    public static String dial(Context c, String number) {
        String n = number == null ? "" : number.replaceAll("[^0-9+*#]", "");
        if (n.length() < 3) return "erro: número inválido";
        return start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))), "abri o discador com o número; falta ele tocar em ligar");
    }

    public static String alarm(Context c, int hour, int minute, String label) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return "erro: hora inválida";
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, hour);
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        if (label != null && label.trim().length() > 0) i.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim());
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        return start(c, i, String.format(Locale.US, "criei um alarme para %02d:%02d", hour, minute));
    }

    public static String timer(Context c, int seconds, String label) {
        if (seconds < 1 || seconds > 86400) return "erro: duração inválida";
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
        i.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        if (label != null && label.trim().length() > 0) i.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim());
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        return start(c, i, "iniciei um timer de " + seconds + " segundos");
    }

    public static String event(Context c, String title, String begin, int minutes) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(begin == null ? "" : begin.trim());
            if (d == null) return "erro: início inválido";
            Intent i = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, title == null ? "" : title.trim());
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.getTime());
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, d.getTime() + Math.max(5, minutes) * 60000L);
            return start(c, i, "abri a agenda com o evento preenchido; falta ele salvar");
        } catch (Exception e) {
            return "erro: início inválido; use yyyy-MM-dd HH:mm";
        }
    }

    public static String settings(Context c, String screen) {
        screen = screen == null ? "" : screen.trim().toLowerCase(Locale.ROOT);
        String action;
        if (screen.startsWith("wi")) action = Settings.ACTION_WIFI_SETTINGS;
        else if (screen.startsWith("blu")) action = Settings.ACTION_BLUETOOTH_SETTINGS;
        else if (screen.startsWith("bat")) action = Intent.ACTION_POWER_USAGE_SUMMARY;
        else if (screen.startsWith("som")) action = Settings.ACTION_SOUND_SETTINGS;
        else if (screen.startsWith("tela")) action = Settings.ACTION_DISPLAY_SETTINGS;
        else if (screen.startsWith("app")) action = Settings.ACTION_APPLICATION_SETTINGS;
        else if (screen.startsWith("loc")) action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
        else if (screen.startsWith("dad")) action = Settings.ACTION_DATA_ROAMING_SETTINGS;
        else if (screen.startsWith("not")) action = "android.settings.NOTIFICATION_SETTINGS";
        else action = Settings.ACTION_SETTINGS;
        return start(c, new Intent(action), "abri as configurações (" + (screen.length() == 0 ? "geral" : screen) + ")");
    }
}
