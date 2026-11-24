package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The limits of what Jarvis may do on the screen. They live in code, not in his personality:
 * - apps he never reads or touches (banks, payments, password and code apps), a list the user edits;
 * - buttons that send, pay, erase or install need a "yes" spoken by the user after Jarvis asked;
 * - a record of every action, for the user to check.
 */
public class JarvisGuard {

    private static final String BLOCKED = "bloqueio";
    private static final String LOG_FILE = "jarvis_acoes.log";
    private static final int LOG_MAX = 40000;

    /** Pieces of package names. A package that contains one of them is off limits. */
    private static final String[] DEFAULT_BLOCKED = {
            "bank", "banco", "itau", "bradesco", "santander", "nubank", "com.nu.", "caixa", "com.bb.", "sicoob", "sicredi",
            "safra", "stone", "mercadopago", "picpay", "intermedium", "c6bank", "pagbank", "uol.ps", "next.", "original.",
            "paypal", "wallet", "spay", "binance", "bitcoin", "crypto", "xpinvest", "rico.", "btg", "corretora",
            "authenticator", "authy", "otp", "password", "1password", "bitwarden", "keepass", "lastpass", "dashlane",
            "gov.br", "meugov", "receita", "serasa"
    };

    private static final Pattern RISKY = Pattern.compile(
            "(?i)(^|[^\\p{L}])(enviar|envia|send|pagar|pague|pay|comprar|compre|buy|finalizar|confirmar|confirm|transferir|pix"
                    + "|excluir|apagar|deletar|delete|remover|desinstalar|uninstall|instalar|install|publicar|postar|post"
                    + "|aceitar|accept|assinar|subscribe|sacar|doar|encerrar conta)([^\\p{L}]|$)");

    private static String pendingLabel;
    private static int pendingTurn = -1;

    // ---- protected apps ----

    public static List<String> blocked(Context c) {
        SharedPreferences p = JarvisMemory.state(c);
        String saved = p.getString(BLOCKED, null);
        List<String> list = new ArrayList<>();
        if (saved == null) {
            list.addAll(Arrays.asList(DEFAULT_BLOCKED));
        } else {
            for (String s : saved.split("\n")) if (s.trim().length() > 0) list.add(s.trim());
        }
        return list;
    }

    private static void save(Context c, List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) b.append(s).append('\n');
        JarvisMemory.state(c).edit().putString(BLOCKED, b.toString()).apply();
    }

    public static boolean isBlocked(Context c, CharSequence pkg) {
        if (pkg == null) return false;
        String name = pkg.toString().toLowerCase(Locale.ROOT);
        for (String piece : blocked(c)) if (name.contains(piece)) return true;
        return false;
    }

    public static String block(Context c, String piece) {
        piece = piece == null ? "" : piece.trim().toLowerCase(Locale.ROOT);
        if (piece.length() < 3) return "jarvis: informe pelo menos três letras do nome do pacote do app.";
        List<String> list = blocked(c);
        if (!list.contains(piece)) list.add(piece);
        save(c, list);
        return "jarvis: ele não entra mais em apps cujo pacote contém \"" + piece + "\".";
    }

    public static String unblock(Context c, String piece) {
        piece = piece == null ? "" : piece.trim().toLowerCase(Locale.ROOT);
        List<String> list = blocked(c);
        if (!list.remove(piece)) return "jarvis: \"" + piece + "\" não está na lista.";
        save(c, list);
        return "jarvis: \"" + piece + "\" saiu da lista de apps protegidos. Ele passa a poder ler e tocar nesses apps.";
    }

    public static String resetBlocked(Context c) {
        JarvisMemory.state(c).edit().remove(BLOCKED).apply();
        return "jarvis: lista de apps protegidos de volta ao padrão.";
    }

    // ---- actions that need a spoken yes ----

    public static boolean isRisky(String label) {
        return label != null && RISKY.matcher(label).find();
    }

    /**
     * Null when the action may go on. Otherwise the text returned to the model: it must ask the user out loud,
     * and the same action is only allowed in a later turn, that is, after the user spoke again.
     */
    public static String needsConfirmation(String label) {
        int turn = JarvisClient.userTurns();
        if (label.equals(pendingLabel) && turn > pendingTurn) {
            pendingLabel = null;
            pendingTurn = -1;
            return null;
        }
        pendingLabel = label;
        pendingTurn = turn;
        return "BLOQUEADO ATÉ CONFIRMAR: \"" + label + "\" envia, paga, apaga ou instala algo. Pare aqui, diga a ele em voz alta "
                + "exatamente o que vai acontecer e pergunte se pode. Só chame tocar de novo, no mesmo botão, depois que ele "
                + "responder que sim. Se ele disser não, não toque.";
    }

    // ---- record ----

    public static synchronized void log(Context c, String line) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), LOG_FILE);
            if (f.length() > LOG_MAX) {
                String old = JarvisPersona.readFile(f);
                FileOutputStream out = new FileOutputStream(f, false);
                try {
                    out.write(old.substring(old.length() / 2).getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date()) + "  " + line + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {}
    }

    /** The last lines of the record. */
    public static synchronized String tail(Context c, int lines) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), LOG_FILE);
            if (!f.isFile()) return "(ele ainda não fez nada na tela)";
            String[] all = JarvisPersona.readFile(f).split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) b.append(all[i]).append('\n');
            return b.toString().trim();
        } catch (Exception e) {
            return "(não foi possível ler o registro)";
        }
    }
}
