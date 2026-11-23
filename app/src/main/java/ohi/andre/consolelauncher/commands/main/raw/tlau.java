package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Context;
import android.graphics.Color;

import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.managers.tlau.TlauSetup;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * "t-lau" (typed with the hyphen; MainManager turns it into this class name): settings of the launcher itself.
 *
 *   t-lau -config -default    walk-through of everything that must be on (one step at a time, "ok" to go on)
 *   t-lau -config -status     the same list, only checked
 *
 * "-config" is the place for future settings of the app.
 */
public class tlau implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        Context c = pack.context;
        String line = pack.getString().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        if (line.equals("-config -default") || line.equals("-config default")) {
            new TlauSetup().begin((MainPack) pack);
            return null;
        }

        if (line.equals("-config -status") || line.equals("-config status") || line.equals("-status")) {
            Tuils.sendOutput(Color.CYAN, c, TlauSetup.report(c));
            return null;
        }

        return c.getString(helpRes());
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
        return R.string.help_tlau;
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
