package ohi.andre.consolelauncher.managers.tlau;

import android.content.Context;
import android.graphics.Color;

import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.RedirectCommand;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * "t-lau -user": who is using the launcher. Two profiles of the same person: Teetsuuo (the default, the
 * hacker side: location and the fun tools) and Erick (the everyday side: payments, mail and the like, to be built).
 * For now the only difference is the boot art: T-LAU or E-LAU.
 */
public class TlauUser extends RedirectCommand {

    public static final String TEETSUUO = "teetsuuo";
    public static final String ERICK = "erick";

    private static final String PREFS = "tlau";
    private static final String USER = "user";

    private static final String ART_T =
            " _____         _         _     _   _\n" +
            "|_   _|       | |       / \\   | | | |\n" +
            "  | |   _____ | |      / _ \\  | | | |\n" +
            "  | |  |_____|| |___  / ___ \\ | |_| |\n" +
            "  |_|         |_____|/_/   \\_\\ \\___/\n";

    private static final String ART_E =
            " _____         _         _     _   _\n" +
            "| ____|       | |       / \\   | | | |\n" +
            "|  _|   _____ | |      / _ \\  | | | |\n" +
            "| |___ |_____|| |___  / ___ \\ | |_| |\n" +
            "|_____|       |_____|/_/   \\_\\ \\___/\n";

    public static String current(Context c) {
        String u = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(USER, TEETSUUO);
        return ERICK.equals(u) ? ERICK : TEETSUUO;
    }

    /** Accepts the name or its number in the list. Returns the profile set, or null when it is not one of the two. */
    public static String set(Context c, String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String user;
        if (n.equals("1") || n.equals(TEETSUUO) || n.equals("teetsuo") || n.equals("t")) user = TEETSUUO;
        else if (n.equals("2") || n.equals(ERICK) || n.equals("e")) user = ERICK;
        else return null;

        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(USER, user).apply();
        return user;
    }

    public static String list(Context c) {
        String u = current(c);
        return "== t-lau: usuários ==\n"
                + (TEETSUUO.equals(u) ? "* " : "  ") + "1. Teetsuuo  (T-LAU: o lado hacker)\n"
                + (ERICK.equals(u) ? "* " : "  ") + "2. Erick     (E-LAU: o dia a dia)";
    }

    /** The boot art of the current profile, with its caption. */
    public static String art(Context c) {
        boolean erick = ERICK.equals(current(c));
        return (erick ? ART_E : ART_T) + "\n" + (erick ? "[ E-LAU :: ERICK ]" : "[ T-LAU :: TEETSUUO LAUNCHER ]") + "\n\n";
    }

    /** Prints the profile now in use and its art. */
    public static void announce(Context c, int artColor) {
        Tuils.sendOutput(Color.CYAN, c, "t-lau: usuário = " + (ERICK.equals(current(c)) ? "Erick" : "Teetsuuo"));
        Tuils.sendOutput(artColor, c, art(c));
    }

    private final int artColor;

    public TlauUser(int artColor) {
        this.artColor = artColor;
    }

    @Override
    public String onRedirect(ExecutePack pack) {
        String typed = afterObjects.isEmpty() ? "" : String.valueOf(afterObjects.get(afterObjects.size() - 1));

        cleanup();
        ((MainPack) pack).redirectator.cleanup();

        if (set(pack.context, typed) == null) {
            Tuils.sendOutput(Color.CYAN, pack.context, "t-lau: não é um dos dois; nada mudou. Use: t-lau -user teetsuuo ou t-lau -user erick");
            return null;
        }

        announce(pack.context, artColor);
        return null;
    }

    @Override
    public int getHint() {
        return R.string.tlau_user_hint;
    }

    @Override
    public boolean isWaitingPermission() {
        return false;
    }

    // this object is never registered as a command: the methods below are not used

    @Override
    public String exec(ExecutePack pack) {
        return null;
    }

    @Override
    public int[] argType() {
        return new int[0];
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public int helpRes() {
        return R.string.help_tlau;
    }

    @Override
    public String onArgNotFound(ExecutePack pack, int indexNotFound) {
        return null;
    }

    @Override
    public String onNotArgEnough(ExecutePack pack, int nArgs) {
        return null;
    }
}
