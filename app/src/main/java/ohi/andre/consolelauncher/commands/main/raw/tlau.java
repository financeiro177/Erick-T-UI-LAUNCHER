package ohi.andre.consolelauncher.commands.main.raw;

import android.content.Context;
import android.graphics.Color;

import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.commands.main.MainPack;
import ohi.andre.consolelauncher.managers.ai.AiConfig;
import ohi.andre.consolelauncher.managers.ai.AiUsage;
import ohi.andre.consolelauncher.managers.tlau.TlauBackup;
import ohi.andre.consolelauncher.managers.tlau.TlauSetup;
import ohi.andre.consolelauncher.managers.tlau.TlauUser;
import ohi.andre.consolelauncher.managers.xml.XMLPrefsManager;
import ohi.andre.consolelauncher.managers.xml.options.Theme;
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
        // the "-" is optional: "t-lau config ia modelo" works like "t-lau -config -ia -modelo"
        String raw = pack.getString().trim().replaceAll("\\s+", " ");
        String[] words = raw.length() == 0 ? new String[0] : raw.split(" ");
        String[] w = new String[words.length];
        for (int k = 0; k < words.length; k++) w[k] = words[k].toLowerCase(Locale.ROOT).replaceFirst("^-+", "");

        String a = w.length > 0 ? w[0] : "", b = w.length > 1 ? w[1] : "", d = w.length > 2 ? w[2] : "";
        int artColor = XMLPrefsManager.getColor(Theme.input_color);

        if (a.equals("user") || a.equals("usuario") || a.equals("usuário")) {
            if (b.length() > 0) {
                if (TlauUser.set(c, b) == null) return say(c, "t-lau: use teetsuuo ou erick.\n" + TlauUser.list(c));
                TlauUser.announce(c, artColor);
                return null;
            }

            say(c, TlauUser.list(c) + "\nQuem está mexendo? (digite o nome ou o número)");
            ((MainPack) pack).redirectator.prepareRedirection(new TlauUser(artColor));
            return null;
        }

        if (a.equals("status")) return say(c, TlauSetup.report(c));

        if (a.equals("config")) {
            if (b.equals("default") || b.equals("padrao") || b.equals("padrão")) {
                new TlauSetup().begin((MainPack) pack);
                return null;
            }
            if (b.equals("status")) return say(c, TlauSetup.report(c));
            if (b.equals("backup")) return say(c, TlauBackup.backup(c));
            if (b.equals("restore") || b.equals("restaurar")) return say(c, TlauBackup.restore(c));

            if (b.equals("ia") || b.equals("ai")) {
                if (d.equals("modelo") || d.equals("model") || d.equals("modelos")) {
                    if (w.length > 3) {
                        String m = AiConfig.resolveModel(w[3]);
                        if (m == null) return say(c, "t-lau: modelo desconhecido.\n" + AiConfig.modelList(c));
                        AiConfig.setModel(c, m);
                        return say(c, m.equals(AiConfig.defaultModel()) ? "t-lau: modelo = " + m + " (o padrão)"
                                : "t-lau: modelo = " + m + " só nesta conversa; ao encerrar volta ao padrão");
                    }
                    return say(c, "O modelo é o \"cérebro\" que responde no comando ai. O mais barato é sempre o padrão; "
                            + "um mais forte custa mais e vale só para a conversa em que você o escolher.\n" + AiConfig.modelList(c)
                            + "\nPor aqui: t-lau -config -ia -modelo sonnet");
                }

                double limit = AiConfig.limitUsd(c);
                int warn = AiConfig.warnTokens(c);
                return say(c, "== t-lau: IA ==\n"
                        + "modelo:  " + AiConfig.model(c).replace("claude-", "") + "  (t-lau -config -ia -modelo)\n"
                        + "limite:  " + (limit > 0 ? AiUsage.money(limit) : "desligado") + "  (ai -limite 100)\n"
                        + "ciclo:   dia " + AiConfig.cycleDay(c) + "  (ai -ciclo 8)\n"
                        + "aviso:   " + (warn > 0 ? warn + " tokens" : "desligado") + "  (ai -aviso 3000)\n"
                        + "chave:   " + AiConfig.keyHint(c) + "  (ai -key)\n"
                        + "gasto e uso: ai -custo | memória: ai -memory");
            }

            return say(c, "== t-lau -config ==\n"
                    + "-default -> passo a passo do que precisa estar ativado\n"
                    + "-status  -> o que está ativado e o que falta\n"
                    + "-ia      -> ajustes da IA (-ia -modelo lista e explica os modelos)\n"
                    + "-backup  -> grava locais, memória, lembretes e ajustes em " + TlauBackup.where() + "\n"
                    + "-restore -> recupera o que está nesse arquivo");
        }

        return c.getString(helpRes());
    }

    private static String say(Context c, String text) {
        Tuils.sendOutput(Color.CYAN, c, text);
        return null;
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
