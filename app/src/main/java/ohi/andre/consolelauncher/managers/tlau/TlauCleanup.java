package ohi.andre.consolelauncher.managers.tlau;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Environment;

import java.io.File;

import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * After the app is installed or updated, removes the installer files left in Downloads
 * (app-fdroid-debug*.apk and tui-launcher-apk*.zip, the names the GitHub build produces). Nothing else is touched.
 * Needs the "all files" access the launcher already asks for; without it, it tries again on the next start.
 */
public class TlauCleanup {

    private static final String PREFS = "tlau_cleanup";
    private static final String LAST = "last_update";

    public static void runIfUpdated(final Context context) {
        final Context c = context.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long updated = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
                    SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    if (p.getLong(LAST, 0) == updated) return;

                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) return;

                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File[] files = dir == null ? null : dir.listFiles();
                    if (files == null) return;

                    int removed = 0;
                    for (File f : files) {
                        if (!f.isFile()) continue;

                        String n = f.getName().toLowerCase();
                        boolean installer = (n.startsWith("app-fdroid-debug") && n.endsWith(".apk"))
                                || ((n.startsWith("tui-launcher-apk") || n.startsWith("t-lau-apk")) && n.endsWith(".zip"));
                        if (installer && f.delete()) removed++;
                    }

                    p.edit().putLong(LAST, updated).apply();
                    if (removed > 0) {
                        Tuils.sendOutput(Color.CYAN, c, "t-lau: app atualizado; " + removed + " arquivo(s) de instalação removido(s) de Downloads.");
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }
}
