package ohi.andre.consolelauncher.managers.tlau;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.managers.ai.AiConfig;
import ohi.andre.consolelauncher.managers.ai.AiMemory;
import ohi.andre.consolelauncher.managers.mock.MockLocationStore;
import ohi.andre.consolelauncher.managers.reminder.ReminderScheduler;
import ohi.andre.consolelauncher.managers.reminder.ReminderStore;
import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Ui;

/**
 * "t-lau -config -backup" / "-restore": the app's own data in one readable file, Documents/T-Lau/t-lau-backup.json,
 * a folder that survives uninstalling. It holds the saved places, the assistant's memory, the reminders, the AI
 * settings, the name and the user. The API key is never written. System settings (default launcher, mock location
 * app, battery, permissions) cannot be saved by an app: "t-lau -config -default" walks through those.
 */
public class TlauBackup {

    private static final String FOLDER = "T-Lau";
    private static final String FILE = "t-lau-backup.json";

    private static File file() {
        return new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER), FILE);
    }

    public static String where() {
        return "Documentos/" + FOLDER + "/" + FILE;
    }

    /** Without "all files" access the file could not be read back after a reinstall. Opens the screen to grant it. */
    private static String needsAccess(Context c) {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return null;

        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", c.getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
        return "t-lau: para ler e gravar o backup, ligue \"Acesso a todos os arquivos\" para o T-Lau na tela que abri e repita o comando.";
    }

    /** One line for "t-lau -config -status". */
    public static String statusLine() {
        try {
            File f = file();
            if (f.exists()) return "backup: " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(f.lastModified()));
        } catch (Exception ignored) {}
        return "backup: nenhum (t-lau -config -backup)";
    }

    public static String backup(Context c) {
        String problem = needsAccess(c);
        if (problem != null) return problem;

        try {
            JSONObject root = new JSONObject();
            root.put("app", "t-lau");
            root.put("formato", 1);
            root.put("criado", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));

            JSONArray places = new JSONArray();
            for (MockLocationStore.Place p : MockLocationStore.places(c)) {
                places.put(new JSONObject().put("nome", p.name).put("lat", p.lat).put("lon", p.lon).put("alt", p.alt));
            }
            root.put("locais", places);

            JSONObject memory = new JSONObject();
            for (String k : AiMemory.keys(c)) memory.put(k, AiMemory.get(c, k));
            root.put("memoria", memory);

            JSONArray reminders = new JSONArray();
            for (ReminderStore.Reminder r : ReminderStore.all(c)) {
                reminders.put(new JSONObject()
                        .put("titulo", r.title).put("detalhe", r.body).put("quando", r.when).put("origem", r.source)
                        .put("acao_tipo", r.actionType).put("acao_alvo", r.actionTarget).put("acao_texto", r.actionText)
                        .put("acao_app", r.actionApp).put("acao_direto", r.actionDirect));
            }
            root.put("lembretes", reminders);

            root.put("ia", new JSONObject()
                    .put("limite_usd", AiConfig.limitUsd(c))
                    .put("ciclo_dia", AiConfig.cycleDay(c))
                    .put("aviso_tokens", AiConfig.warnTokens(c)));

            root.put("usuario", TlauUser.current(c));

            String name = XMLPrefsManager.get(Ui.username);
            String device = XMLPrefsManager.get(Ui.deviceName);
            if (name != null) root.put("nome", name);
            if (device != null) root.put("aparelho", device);

            File f = file();
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return "t-lau: não foi possível criar a pasta " + where();

            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(root.toString(2));
            } finally {
                w.close();
            }

            return "t-lau: backup gravado em " + where() + "\n"
                    + places.length() + " locais, " + memory.length() + " itens de memória, " + reminders.length() + " lembretes.\n"
                    + "A chave da IA não é gravada. O arquivo é texto legível: quem abrir a pasta consegue ler.";
        } catch (Exception e) {
            return "t-lau: não foi possível gravar o backup.";
        }
    }

    public static String restore(Context c) {
        String problem = needsAccess(c);
        if (problem != null) return problem;

        File f = file();
        if (!f.exists()) return "t-lau: não há backup em " + where() + ". Crie um com: t-lau -config -backup";

        try {
            StringBuilder text = new StringBuilder();
            InputStreamReader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
            try {
                char[] buffer = new char[4096];
                int n;
                while ((n = r.read(buffer)) > 0) text.append(buffer, 0, n);
            } finally {
                r.close();
            }

            JSONObject root = new JSONObject(text.toString());
            if (!"t-lau".equals(root.optString("app"))) return "t-lau: o arquivo em " + where() + " não é um backup do T-Lau.";

            int nPlaces = 0, nMemory = 0, nReminders = 0;

            JSONArray places = root.optJSONArray("locais");
            for (int i = 0; places != null && i < places.length(); i++) {
                JSONObject p = places.getJSONObject(i);
                String name = p.optString("nome").trim();
                if (name.length() == 0 || !p.has("lat") || !p.has("lon")) continue;
                if (MockLocationStore.save(c, new MockLocationStore.Place(name, p.getDouble("lat"), p.getDouble("lon"), p.optDouble("alt", 0)))) nPlaces++;
            }

            JSONObject memory = root.optJSONObject("memoria");
            if (memory != null) {
                JSONArray keys = memory.names();
                for (int i = 0; keys != null && i < keys.length(); i++) {
                    String k = keys.getString(i);
                    if (AiMemory.put(c, k, memory.optString(k)) == null) nMemory++;
                }
            }

            // a reminder already there (same title and time) is not added twice
            List<ReminderStore.Reminder> existing = ReminderStore.all(c);
            JSONArray reminders = root.optJSONArray("lembretes");
            for (int i = 0; reminders != null && i < reminders.length(); i++) {
                JSONObject o = reminders.getJSONObject(i);
                String title = o.optString("titulo").trim();
                long when = o.optLong("quando", 0);
                if (title.length() == 0 || when <= 0) continue;

                boolean duplicate = false;
                for (ReminderStore.Reminder e : existing) if (e.when == when && e.title.equals(title)) duplicate = true;
                if (duplicate) continue;

                ReminderStore.Reminder action = new ReminderStore.Reminder();
                action.actionType = o.optString("acao_tipo");
                action.actionTarget = o.optString("acao_alvo");
                action.actionText = o.optString("acao_texto");
                action.actionApp = o.optString("acao_app");
                action.actionDirect = o.optBoolean("acao_direto", false);

                ReminderStore.Reminder added = ReminderStore.add(c, title, o.optString("detalhe"), when, o.optString("origem"), action);
                if (added != null) {
                    ReminderScheduler.schedule(c, added);
                    nReminders++;
                }
            }

            JSONObject ai = root.optJSONObject("ia");
            if (ai != null) {
                double limit = ai.optDouble("limite_usd", -1);
                int day = ai.optInt("ciclo_dia", -1);
                int warn = ai.optInt("aviso_tokens", -1);
                if (limit >= 0 && limit <= 100000) AiConfig.setLimitUsd(c, limit);
                if (day >= 1 && day <= 28) AiConfig.setCycleDay(c, day);
                if (warn >= 0) AiConfig.setWarnTokens(c, warn);
            }

            TlauUser.set(c, root.optString("usuario"));

            String name = root.optString("nome").trim();
            String device = root.optString("aparelho").trim();
            if (name.length() > 0) XMLPrefsManager.XMLPrefsRoot.UI.write(Ui.username, name);
            if (device.length() > 0) XMLPrefsManager.XMLPrefsRoot.UI.write(Ui.deviceName, device);

            return "t-lau: backup de " + root.optString("criado", "?") + " restaurado.\n"
                    + nPlaces + " locais, " + nMemory + " itens de memória, " + nReminders + " lembretes novos.\n"
                    + "Falta só o que o backup não guarda: a chave (ai -key) e os ajustes do sistema (t-lau -config -default).\n"
                    + "O nome aparece ao reabrir o T-Lau.";
        } catch (Exception e) {
            return "t-lau: não foi possível ler o backup (arquivo danificado?).";
        }
    }
}
