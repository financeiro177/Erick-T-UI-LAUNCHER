package ohi.andre.consolelauncher.managers.mock;

import android.content.Context;
import android.location.Location;

import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.RedirectCommand;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Second step of "loc -add": the next line typed in the terminal arrives here as the place name,
 * and the current real position is saved under it.
 */
public class LocAddPrompt extends RedirectCommand {

    @Override
    public String onRedirect(ExecutePack pack) {
        String name = afterObjects.isEmpty() ? null : String.valueOf(afterObjects.get(0));

        cleanup();
        ((MainPack) pack).redirectator.cleanup();

        final Context context = pack.context;

        final String placeName = normalize(name);
        if (placeName == null) {
            Tuils.sendOutput(MockLocationService.CYAN, context, "loc: nome inválido, nada salvo");
            return null;
        }

        if (MockLocationStore.state(context).isOn()) {
            Tuils.sendOutput(MockLocationService.CYAN, context, "loc: desligue o mock antes (loc -reset)");
            return null;
        }

        Tuils.sendOutput(MockLocationService.CYAN, context, "loc: obtendo posição...");
        RealLocation.get(context, new RealLocation.Callback() {
            @Override
            public void onResult(Location l) {
                if (l == null) {
                    Tuils.sendOutput(MockLocationService.CYAN, context, "loc: sem posição do GPS, tente de novo");
                    return;
                }

                boolean existed = MockLocationStore.place(context, placeName) != null;
                boolean ok = MockLocationStore.save(context, new MockLocationStore.Place(placeName, l.getLatitude(), l.getLongitude(),
                        l.hasAltitude() ? l.getAltitude() : 0));

                if (!ok) {
                    Tuils.sendOutput(MockLocationService.CYAN, context, "loc: erro ao salvar");
                    return;
                }

                Tuils.sendOutput(MockLocationService.CYAN, context, String.format(Locale.US, "%s: %s (%.5f, %.5f)",
                        existed ? "atualizado" : "salvo", placeName, l.getLatitude(), l.getLongitude()));
            }
        });

        return null;
    }

    /** Names are a single lowercase word, so they can be typed back as an argument. */
    public static String normalize(String name) {
        if (name == null) return null;
        name = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (name.length() == 0 || name.startsWith("-")) return null;
        return name;
    }

    @Override
    public int getHint() {
        return R.string.loc_name_hint;
    }

    @Override
    public boolean isWaitingPermission() {
        return false;
    }

//    this object is never registered as a command: the methods below are not used

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
        return R.string.help_loc;
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
