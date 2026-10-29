package ohi.andre.consolelauncher.commands.main.raw;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.managers.TerminalManager;
import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * Comando "profile": perfil real e rapido do aparelho, estilo "scanner" de ctOS,
 * com avisos quando bateria, memoria ou armazenamento estao em estado critico.
 */
public class profile implements CommandAbstraction {

    @Override
    public String exec(final ExecutePack pack) throws Exception {
        final Context context = pack.context.getApplicationContext();

        final List<String> lines = new ArrayList<>();
        lines.add("> acessando node local...");
        lines.add("> coletando telemetria...");
        lines.add("");

        // Bateria
        int batteryPct = -1;
        boolean charging = false;
        try {
            Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPct = Math.round(level * 100f / scale);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) {}
        lines.add("BATERIA ........ " + (batteryPct >= 0 ? batteryPct + "%" + (charging ? " (carregando)" : "") : "indisponivel"));

        // Armazenamento interno
        long storageTotal = -1, storageAvail = -1;
        try {
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            storageTotal = stat.getTotalBytes();
            storageAvail = stat.getAvailableBytes();
            lines.add("ARMAZENAMENTO .. " + formatGb(storageTotal - storageAvail) + " / " + formatGb(storageTotal));
        } catch (Exception e) {
            lines.add("ARMAZENAMENTO .. indisponivel");
        }

        // Memoria RAM
        long ramTotal = -1, ramAvail = -1;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramTotal = mi.totalMem;
            ramAvail = mi.availMem;
            lines.add("MEMORIA ........ " + formatGb(ramTotal - ramAvail) + " / " + formatGb(ramTotal));
        } catch (Exception e) {
            lines.add("MEMORIA ........ indisponivel");
        }

        // Rede
        String networkLabel = "sem conexao";
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = cm.getActiveNetworkInfo();
            if (active != null && active.isConnected()) {
                if (active.getType() == ConnectivityManager.TYPE_WIFI) {
                    WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                    String ssid = info != null ? info.getSSID() : null;
                    if (ssid != null) ssid = ssid.replace("\"", "");
                    boolean known = ssid != null && !ssid.contains("unknown ssid");
                    networkLabel = (known ? ssid : "Wi-Fi") + (info != null ? " (" + info.getRssi() + " dBm)" : "");
                } else {
                    networkLabel = "Dados moveis";
                }
            }
        } catch (Exception e) {
            networkLabel = "indisponivel";
        }
        lines.add("REDE ........... " + networkLabel);

        // Apps instalados
        try {
            int count = context.getPackageManager().getInstalledApplications(0).size();
            lines.add("APPS ........... " + count + " instalados");
        } catch (Exception ignored) {}

        lines.add("");

        // Avisos criticos
        final List<String> warnings = new ArrayList<>();
        if (batteryPct >= 0 && batteryPct <= 20 && !charging) {
            warnings.add("[!] Bateria critica: " + batteryPct + "%");
        }
        if (ramTotal > 0) {
            float ramFreePct = ramAvail * 100f / ramTotal;
            if (ramFreePct < 15f) warnings.add("[!] Memoria baixa: " + Math.round(ramFreePct) + "% livre");
        }
        if (storageTotal > 0) {
            float storageFreePct = storageAvail * 100f / storageTotal;
            if (storageFreePct < 10f) warnings.add("[!] Armazenamento critico: " + Math.round(storageFreePct) + "% livre");
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final int delay = 110;
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Tuils.sendOutput(context, line, TerminalManager.CATEGORY_OUTPUT);
                }
            }, i * delay);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (warnings.isEmpty()) {
                    Tuils.sendOutput(Color.GREEN, context, "status: tudo normal.", TerminalManager.CATEGORY_OUTPUT);
                } else {
                    for (String w : warnings) {
                        Tuils.sendOutput(Color.RED, context, w, TerminalManager.CATEGORY_OUTPUT);
                    }
                }
            }
        }, lines.size() * delay + 80);

        return null;
    }

    private static String formatGb(long bytes) {
        return String.format(Locale.getDefault(), "%.1fGB", bytes / 1073741824.0);
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public int[] argType() {
        return new int[0];
    }

    @Override
    public int helpRes() {
        return R.string.help_profile;
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
