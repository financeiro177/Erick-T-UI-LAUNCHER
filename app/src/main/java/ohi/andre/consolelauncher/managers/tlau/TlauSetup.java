package ohi.andre.consolelauncher.managers.tlau;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.commands.main.specific.RedirectCommand;
import ohi.andre.consolelauncher.managers.ai.AiConfig;
import ohi.andre.consolelauncher.managers.mock.MockLocationService;
import ohi.andre.consolelauncher.managers.mock.MockLocationStore;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * "t-lau -config -default": walks through what the launcher needs to work fully. Each step opens the
 * right screen with one line of instruction and waits for "ok", including the steps already in place.
 * The terminal stays redirected here until the last step, "sair" or "pular" through the end.
 */
public class TlauSetup extends RedirectCommand {

    private static final int PERMISSIONS_REQUEST = 7344;
    private static final int ROLE_REQUEST = 7345;

    /** One thing that must be on. */
    public abstract static class Step {
        final String name, instruction;

        Step(String name, String instruction) {
            this.name = name;
            this.instruction = instruction;
        }

        abstract boolean done(Context c);

        /** "again" is true when the user said ok but the check still fails: use the fallback screen. */
        abstract void open(Context c, boolean again);
    }

    public static List<Step> steps() {
        List<Step> s = new ArrayList<>();

        s.add(new Step("launcher padrão", "Escolha T-Lau como app de início (tela inicial).") {
            @Override
            boolean done(Context c) {
                return isDefaultHome(c);
            }

            @Override
            void open(Context c, boolean again) {
                try {
                    if (!again && Build.VERSION.SDK_INT >= 29 && c instanceof Activity) {
                        RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                            ((Activity) c).startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                start(c, new Intent(Settings.ACTION_HOME_SETTINGS));
            }
        });

        s.add(new Step("localização fictícia", "Em Opções do desenvolvedor, toque em \"Selecionar app de localização fictícia\" e escolha T-Lau.") {
            @Override
            boolean done(Context c) {
                return MockLocationService.isMockAppSelected(c);
            }

            @Override
            void open(Context c, boolean again) {
                if (again || !start(c, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))) {
                    say(c, "  Se as Opções do desenvolvedor não existem: Sobre o telefone > Informações do software > toque 7 vezes em \"Número de compilação\".");
                    if (again) start(c, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    else start(c, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                }
            }
        });

        s.add(new Step("bateria sem restrição", "Nesta tela do app, toque em Bateria e escolha \"Sem restrição\".") {
            @Override
            boolean done(Context c) {
                try {
                    PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                    return Build.VERSION.SDK_INT < 23 || (pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            void open(Context c, boolean again) {
                appDetails(c);
            }
        });

        s.add(new Step("permissões (localização, notificações, contatos)", "Toque em Permitir em cada janela que aparecer.") {
            @Override
            boolean done(Context c) {
                return missingPermissions(c).isEmpty();
            }

            @Override
            void open(Context c, boolean again) {
                List<String> missing = missingPermissions(c);
                if (!again && c instanceof Activity && !missing.isEmpty()) {
                    try {
                        ActivityCompat.requestPermissions((Activity) c, missing.toArray(new String[0]), PERMISSIONS_REQUEST);
                        return;
                    } catch (Exception ignored) {}
                }
                say(c, "  Nesta tela do app: Permissões > libere Localização, Notificações e Contatos.");
                appDetails(c);
            }
        });

        return s;
    }

    public static List<String> missingPermissions(Context c) {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        wanted.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS);

        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (ContextCompat.checkSelfPermission(c, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing;
    }

    public static boolean isDefaultHome(Context c) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean start(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void appDetails(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", c.getPackageName(), null));
        if (!start(c, i)) say(c, "  Abra: Configurações > Aplicativos > T-Lau.");
    }

    private static void say(Context c, String text) {
        Tuils.sendOutput(Color.CYAN, c, text);
    }

    private static void warn(Context c, String text) {
        Tuils.sendOutput(Color.YELLOW, c, text);
    }

    /** What "t-lau -config -status" prints, and the end of the walk-through. */
    public static String report(Context c) {
        StringBuilder b = new StringBuilder("== t-lau: configuração ==");
        int pending = 0;

        for (Step s : steps()) {
            boolean ok = s.done(c);
            if (!ok) pending++;
            b.append('\n').append(ok ? "[ok] " : "[  ] ").append(s.name);
        }

        boolean key = AiConfig.hasKey(c);
        int places = 0;
        try {
            places = MockLocationStore.places(c).size();
        } catch (Exception ignored) {}

        b.append('\n').append(key ? "[ok] " : "[  ] ").append("chave da IA").append(key ? "" : " (copie no Console e rode: ai -key)");
        b.append('\n').append(places > 0 ? "[ok] " : "[  ] ").append("locais salvos: ").append(places)
                .append(places > 0 ? "" : " (loc -save nome latitude longitude)");

        b.append("\n     ").append(TlauBackup.statusLine());

        if (pending == 0 && key) b.append("\nTudo ativado. O T-Lau está pronto.");
        else if (pending > 0) b.append("\nFaltam ").append(pending).append(" item(ns) do sistema. Rode: t-lau -config -default");
        return b.toString();
    }

    // ---- the walk-through ----

    private final List<Step> steps = steps();
    private int index = -1;
    private boolean retried;

    /** Called by the command. Returns false when there was nothing to do. */
    public boolean begin(MainPack pack) {
        Context c = pack.context;
        say(c, "t-lau: vou abrir um ajuste por vez e só sigo para o próximo quando você digitar ok. (pular = próximo, sair = parar)");

        if (!advance(c)) {
            say(c, report(c));
            return false;
        }

        pack.redirectator.prepareRedirection(this);
        return true;
    }

    /** Opens the next step, even one already in place (the user confirms each with "ok"). False when none is left. */
    private boolean advance(Context c) {
        if (++index >= steps.size()) return false;

        Step s = steps.get(index);
        boolean done = s.done(c);
        retried = false;

        say(c, "Passo " + (index + 1) + " de " + steps.size() + ": " + s.name + "\n  "
                + (done ? "Já está ativado. Confira na tela que abri e digite ok." : s.instruction + " Depois volte aqui e digite ok."));
        s.open(c, done);
        return true;
    }

    private void finish(ExecutePack pack) {
        cleanup();
        ((MainPack) pack).redirectator.cleanup();
        say(pack.context, report(pack.context));
    }

    @Override
    public String onRedirect(ExecutePack pack) {
        Context c = pack.context;
        String typed = afterObjects.isEmpty() ? "" : String.valueOf(afterObjects.get(afterObjects.size() - 1));
        afterObjects.clear();
        typed = typed.trim().toLowerCase(Locale.ROOT);

        if (typed.equals("sair") || typed.equals("cancelar")) {
            finish(pack);
            return null;
        }

        if (index < 0 || index >= steps.size()) {
            finish(pack);
            return null;
        }

        Step s = steps.get(index);

        if (typed.equals("ok")) {
            if (!s.done(c)) {
                warn(c, "t-lau: \"" + s.name + "\" ainda não está ativado. Abrindo de novo; depois digite ok (ou pular).");
                s.open(c, retried);
                retried = true;
                return null;
            }
            say(c, "[ok] " + s.name);
        } else if (typed.equals("pular")) {
            warn(c, "[  ] " + s.name + " (pulado)");
        } else {
            say(c, "t-lau: digite ok quando terminar este passo, pular para o próximo ou sair.");
            return null;
        }

        if (!advance(c)) finish(pack);
        return null;
    }

    @Override
    public int getHint() {
        return R.string.tlau_setup_hint;
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
