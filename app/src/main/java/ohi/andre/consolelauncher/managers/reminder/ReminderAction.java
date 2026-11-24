package ohi.andre.consolelauncher.managers.reminder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.core.content.ContextCompat;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * What the "Ir" button of a reminder does. Everything here only opens something for the user:
 * a message is left written in WhatsApp for them to send, and a call is only placed directly
 * when the reminder was created with that explicit choice.
 */
public class ReminderAction {

    public static final String CALL = "ligar";
    public static final String WHATSAPP = "whatsapp";
    public static final String APP = "abrir_app";
    public static final String LINK = "abrir_link";
    public static final String MAP = "mapa";

    public static final String WA_NORMAL = "normal";
    public static final String WA_BUSINESS = "business";

    private static final String PKG_WA = "com.whatsapp";
    private static final String PKG_WA_BUSINESS = "com.whatsapp.w4b";

    public static boolean isType(String t) {
        return CALL.equals(t) || WHATSAPP.equals(t) || APP.equals(t) || LINK.equals(t) || MAP.equals(t);
    }

    /** Digits with country code for WhatsApp; null when the number has no area code. Brazil (55) is assumed. */
    public static String whatsappNumber(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "").replaceFirst("^0+", "");
        if (d.length() == 10 || d.length() == 11) d = "55" + d;
        return d.length() >= 12 && d.length() <= 15 ? d : null;
    }

    /** One line for the card and the terminal: what "Ir" will do. */
    public static String describe(ReminderStore.Reminder r) {
        if (!r.hasAction()) return "";
        switch (r.actionType) {
            case CALL:
                return (r.actionDirect ? "ligar direto para " : "abrir o discador com ") + r.actionTarget;
            case WHATSAPP:
                return "WhatsApp" + (WA_BUSINESS.equals(r.actionApp) ? " Business" : "") + " para " + r.actionTarget
                        + (r.actionText.length() > 0 ? " com a mensagem pronta" : "");
            case APP:
                return "abrir " + r.actionTarget;
            case LINK:
                return "abrir " + r.actionTarget;
            case MAP:
                return "abrir o mapa em " + r.actionTarget;
            default:
                return "";
        }
    }

    /** Returns null when the action was started, otherwise the reason it could not be. */
    public static String perform(Activity a, ReminderStore.Reminder r) {
        try {
            switch (r.actionType) {
                case CALL: {
                    Uri tel = Uri.fromParts("tel", r.actionTarget.replaceAll("[^0-9+*#]", ""), null);
                    boolean direct = r.actionDirect
                            && ContextCompat.checkSelfPermission(a, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
                    a.startActivity(new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, tel));
                    return null;
                }
                case WHATSAPP: {
                    String number = whatsappNumber(r.actionTarget);
                    if (number == null) return "número sem DDD: " + r.actionTarget;

                    boolean business = WA_BUSINESS.equals(r.actionApp);
                    Uri uri = Uri.parse("https://wa.me/" + number
                            + (r.actionText.length() > 0 ? "?text=" + Uri.encode(r.actionText) : ""));
                    Intent i = new Intent(Intent.ACTION_VIEW, uri);
                    i.setPackage(business ? PKG_WA_BUSINESS : PKG_WA);
                    try {
                        a.startActivity(i);
                    } catch (Exception e) {
                        return "WhatsApp" + (business ? " Business" : "") + " não está instalado";
                    }
                    return null;
                }
                case APP: {
                    Intent launch = findApp(a, r.actionTarget);
                    if (launch == null) return "não achei o app " + r.actionTarget;
                    a.startActivity(launch);
                    return null;
                }
                case LINK: {
                    String url = r.actionTarget.trim();
                    if (!url.startsWith("https://") && !url.startsWith("http://")) return "link inválido";
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return null;
                }
                case MAP: {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(r.actionTarget))));
                    return null;
                }
                default:
                    return "ação desconhecida";
            }
        } catch (Exception e) {
            return "não foi possível abrir";
        }
    }

    private static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    /** Launch intent of the installed app whose name is (or, failing that, contains) "name". */
    public static Intent findApp(Context c, String name) {
        String wanted = plain(name);
        if (wanted.length() == 0) return null;

        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);

        String exact = null, partial = null;
        for (ResolveInfo ri : apps) {
            String label = plain(String.valueOf(ri.loadLabel(pm)));
            if (label.equals(wanted)) {
                exact = ri.activityInfo.packageName;
                break;
            }
            if (partial == null && label.contains(wanted)) partial = ri.activityInfo.packageName;
        }

        String pkg = exact != null ? exact : partial;
        return pkg == null ? null : pm.getLaunchIntentForPackage(pkg);
    }
}
