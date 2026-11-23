package ohi.andre.consolelauncher.commands.main.raw;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Created by francescoandreuzzi on 21/05/2017.
 * Alterado: agora pergunta para onde ir (tela principal do celular ou ficar no T-Lau).
 */

public class exit implements CommandAbstraction {
    @Override
    public String exec(ExecutePack pack) throws Exception {
        final Context context = pack.context;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlertDialog.Builder(context)
                            .setTitle("Sair do T-Lau")
                            .setMessage("Para onde voce quer ir?")
                            .setPositiveButton("Tela principal", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    openStockHome(context);
                                }
                            })
                            .setNegativeButton("Ficar no T-Lau", null)
                            .setNeutralButton(isDefaultHome(context) ? "Trocar launcher padrao" : "Definir T-Lau como padrao", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (isDefaultHome(context)) openHomeSettings(context);
                                    else askToBeDefault(context);
                                }
                            })
                            .show();
                } catch (Exception e) {
                    Tuils.resetPreferredLauncherAndOpenChooser(context);
                }
            }
        });

        return null;
    }

    // true when this app is the launcher the system uses today
    private static boolean isDefaultHome(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && context.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    // Android 10+: system dialog "use T-Lau as home app?"; before that, or if it is refused, the default-apps screen
    private static void askToBeDefault(Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29 && context instanceof android.app.Activity) {
                android.app.role.RoleManager rm = (android.app.role.RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    ((android.app.Activity) context).startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 7343);
                    return;
                }
            }
        } catch (Exception ignored) {}
        openHomeSettings(context);
    }

    // Settings > Apps > Default apps > Home app
    private static void openHomeSettings(Context context) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Tuils.resetPreferredLauncherAndOpenChooser(context);
        }
    }

    // Abre a tela inicial original do celular, sem mudar o launcher padrao.
    private static void openStockHome(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);

            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(home, 0);

            ResolveInfo best = null;
            boolean bestIsSystem = false;
            for (ResolveInfo ri : list) {
                if (ri.activityInfo.packageName.equals(context.getPackageName())) continue;
                boolean isSystem = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (best == null || (isSystem && !bestIsSystem)) {
                    best = ri;
                    bestIsSystem = isSystem;
                }
            }

            if (best == null) {
                Tuils.resetPreferredLauncherAndOpenChooser(context);
                return;
            }

            Intent go = new Intent(home);
            go.setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name));
            go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(go);
        } catch (Exception e) {
            Tuils.resetPreferredLauncherAndOpenChooser(context);
        }
    }

    @Override
    public int[] argType() {
        return new int[0];
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public int helpRes() {
        return R.string.help_exit;
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
