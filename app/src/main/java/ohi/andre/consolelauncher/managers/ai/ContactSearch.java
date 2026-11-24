package ohi.andre.consolelauncher.managers.ai;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Looks a name up in the phone's contacts, on the device. Only the few contacts that match the
 * name are returned (and therefore sent to the API); the address book as a whole never leaves the phone.
 */
public class ContactSearch {

    private static final int MAX_RESULTS = 6;

    private static String plain(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    public static String search(Context c, String name) {
        String wanted = plain(name);
        if (wanted.length() < 2) return "erro: nome muito curto";

        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "erro: o T-Lau não tem permissão de contatos. Peça o número ao usuário, ou diga para liberar em "
                    + "Configurações > Aplicativos > T-Lau > Permissões > Contatos.";
        }

        Set<String> found = new LinkedHashSet<>();
        Cursor cursor = null;
        try {
            cursor = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

            while (cursor != null && cursor.moveToNext() && found.size() < MAX_RESULTS) {
                String display = cursor.getString(0);
                String number = cursor.getString(1);
                if (display == null || number == null) continue;

                if (plain(display).contains(wanted)) found.add(display + ": " + number.replaceAll("[^0-9+]", ""));
            }
        } catch (Exception e) {
            return "erro: não foi possível ler os contatos";
        } finally {
            if (cursor != null) cursor.close();
        }

        if (found.isEmpty()) return "nenhum contato com esse nome";

        StringBuilder b = new StringBuilder();
        for (String f : found) {
            if (b.length() > 0) b.append('\n');
            b.append(f);
        }
        return b.toString();
    }
}
