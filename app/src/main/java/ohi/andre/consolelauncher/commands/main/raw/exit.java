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
 * Alterado: agora pergunta para onde ir (tela principal do celular ou ficar no T-UI).
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
                            .setTitle("Sair do T-UI")
                            .setMessage("Para onde voce quer ir?")
                            .setPositiveButton("Tela principal", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    openStockHome(context);
                                }
                            })
                            .setNegativeButton("Ficar no T-UI", null)
                            .setNeutralButton("Trocar launcher padrao", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Tuils.resetPreferredLauncherAndOpenChooser(context);
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
