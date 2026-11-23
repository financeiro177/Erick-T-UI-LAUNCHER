package ohi.andre.consolelauncher.commands.main.raw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

import ohi.andre.consolelauncher.R;
import ohi.andre.consolelauncher.commands.CommandAbstraction;
import ohi.andre.consolelauncher.commands.ExecutePack;
import ohi.andre.consolelauncher.managers.ai.AiClient;
import ohi.andre.consolelauncher.managers.ai.AiConfig;
import ohi.andre.consolelauncher.managers.ai.AiConversation;
import ohi.andre.consolelauncher.managers.ai.AiUsage;

/**
 * "ai": talks to the Claude API only when the user types it. Normal T-UI commands never reach the API.
 *
 *   ai [phrase]              continues the open conversation (opens one if there is none)
 *   ai -novo [phrase]        clears the conversation and starts another topic
 *   ai -fim                  ends the conversation
 *   ai -custo                tokens and estimated spending
 *   ai -limite [dollars]     limit of the billing cycle
 *   ai -ciclo [day]          day of the month when the plan renews
 *   ai -aviso [tokens]       warns when the conversation passes this size
 *   ai -model [name]         haiku / sonnet / opus
 *   ai -key                  saves the key that is in the clipboard, then clears the clipboard
 *
 * It is a plain CommandAbstraction (not a ParamCommand) so a free sentence can follow "ai".
 * The network call is asynchronous (AiClient); this method only decides what to do and returns null
 * after printing, the same way the "loc" command does.
 */
public class ai implements CommandAbstraction {

    @Override
    public String exec(ExecutePack pack) {
        Context c = pack.context;
        String line = pack.getString().trim();
        if (line.length() == 0) return c.getString(helpRes());

        if (line.startsWith("-")) {
            String[] parts = line.split("\\s+", 2);
            String opt = parts[0].toLowerCase(Locale.ROOT);
            String rest = parts.length > 1 ? parts[1].trim() : "";

            switch (opt) {
                case "-fim":
                    return fim(c);
                case "-novo":
                    return novo(c, rest);
                case "-custo":
                    return custo(c);
                case "-limite":
                    return limite(c, rest);
                case "-ciclo":
                    return ciclo(c, rest);
                case "-aviso":
                    return aviso(c, rest);
                case "-model":
                    return model(c, rest);
                case "-key":
                    return key(c);
                default:
                    // "-word" is taken as a mistyped option; anything else (e.g. "-5 + 3") is a normal phrase
                    if (opt.matches("-[a-zà-ú]+")) return say(c, "ai: opção desconhecida (" + opt + "). Digite ai para ver a ajuda.");
            }
        }

        return chat(c, line);
    }

    // ---- conversation ----

    private static String chat(Context c, String phrase) {
        if (AiConversation.expired(System.currentTimeMillis())) {
            AiConversation.clear();
            say(c, "(conversa anterior encerrada por inatividade)");
        }
        AiClient.ask(c, phrase);
        return null;
    }

    private static String fim(Context c) {
        boolean was = AiConversation.isOpen();
        AiConversation.clear();
        return say(c, was ? "ai: conversa encerrada." : "ai: nenhuma conversa aberta.");
    }

    private static String novo(Context c, String rest) {
        AiConversation.clear();
        if (rest.length() == 0) return say(c, "ai: conversa limpa.");
        AiClient.ask(c, rest);
        return null;
    }

    // ---- usage ----

    private static String custo(Context c) {
        Calendar now = Calendar.getInstance();
        int day = AiConfig.cycleDay(c);
        AiUsage.Total cycle = AiUsage.cycle(c);
        double limit = AiConfig.limitUsd(c);
        Calendar renew = AiUsage.nextRenewal(now, day);
        long daysLeft = Math.max(0, (renew.getTimeInMillis() - now.getTimeInMillis()) / 86400000L);

        StringBuilder b = new StringBuilder();

        b.append("== ai: uso ==\n");
        if (AiConversation.isOpen()) {
            b.append("conversa: ").append(AiConversation.size()).append(" msgs, contexto ~")
                    .append(AiConversation.contextTokens()).append(" tk");
        } else {
            b.append("conversa: nenhuma aberta");
        }

        b.append("\n\n").append(row("hoje", AiUsage.today(c)));
        b.append('\n').append(row("7 dias", AiUsage.lastDays(c, 7)));
        b.append('\n').append(row("ciclo", cycle));
        b.append("\n         (").append(AiUsage.formatDate(AiUsage.cycleStart(now, day))).append(" a ")
                .append(AiUsage.formatDate(renew)).append(")");

        b.append("\n\n");
        if (limit > 0) {
            b.append("limite:  ").append(AiUsage.money(limit));
            b.append(String.format(Locale.US, "\nusado:   %.1f%%", cycle.cost * 100 / limit));
            b.append("\nresta:   ").append(AiUsage.money(Math.max(0, limit - cycle.cost)));
        } else {
            b.append("limite:  desligado");
        }
        b.append("\nrenova:  ").append(AiUsage.formatDate(renew)).append(" (em ").append(daysLeft).append(daysLeft == 1 ? " dia)" : " dias)");

        b.append("\n\nmodelo:  ").append(AiConfig.model(c).replace("claude-", ""));
        b.append("\nchave:   ").append(AiConfig.keyHint(c));
        b.append("\n(tk = tokens; valores estimados aqui, o saldo real fica em Console > Cobrança)");
        return say(c, b.toString());
    }

    // "hoje:    871 tk   US$ 0.0002"
    private static String row(String label, AiUsage.Total t) {
        StringBuilder l = new StringBuilder(label).append(':');
        while (l.length() < 9) l.append(' ');
        return l.append(t.in + t.out).append(" tk   ").append(AiUsage.money(t.cost)).toString();
    }

    private static String limite(Context c, String rest) {
        if (rest.length() == 0) {
            double l = AiConfig.limitUsd(c);
            return say(c, "ai: limite do ciclo: " + (l > 0 ? AiUsage.money(l) : "desligado") + ". Para mudar: ai -limite 100");
        }

        try {
            double v = Double.parseDouble(rest.replace(',', '.'));
            if (v < 0 || v > 100000 || Double.isNaN(v)) throw new NumberFormatException();
            AiConfig.setLimitUsd(c, v);
            return say(c, v > 0 ? "ai: limite do ciclo = " + AiUsage.money(v)
                    : "ai: limite desligado (não recomendado: nada vai te avisar antes do crédito acabar)");
        } catch (NumberFormatException e) {
            return say(c, "ai: valor inválido. Exemplo: ai -limite 100");
        }
    }

    private static String ciclo(Context c, String rest) {
        if (rest.length() == 0) return say(c, "ai: o plano renova no dia " + AiConfig.cycleDay(c) + ". Para mudar: ai -ciclo 15");

        try {
            int d = Integer.parseInt(rest);
            if (d < 1 || d > 28) throw new NumberFormatException();
            AiConfig.setCycleDay(c, d);
            return say(c, "ai: ciclo renova todo dia " + d + " (próxima renovação: "
                    + AiUsage.formatDate(AiUsage.nextRenewal(Calendar.getInstance(), d)) + ")");
        } catch (NumberFormatException e) {
            return say(c, "ai: dia inválido (use de 1 a 28). Exemplo: ai -ciclo 15");
        }
    }

    private static String aviso(Context c, String rest) {
        if (rest.length() == 0) {
            int w = AiConfig.warnTokens(c);
            return say(c, "ai: aviso de tamanho da conversa: " + (w > 0 ? w + " tokens" : "desligado") + ". Para mudar: ai -aviso 3000");
        }

        try {
            int t = Integer.parseInt(rest);
            if (t < 0) throw new NumberFormatException();
            AiConfig.setWarnTokens(c, t);
            return say(c, t > 0 ? "ai: vou avisar quando a conversa passar de " + t + " tokens" : "ai: aviso de tamanho desligado");
        } catch (NumberFormatException e) {
            return say(c, "ai: número inválido. Exemplo: ai -aviso 3000");
        }
    }

    // ---- model and key ----

    private static String model(Context c, String rest) {
        if (rest.length() == 0) return say(c, "ai: modelo atual: " + AiConfig.model(c) + ". Opções: haiku, sonnet, opus");

        String m = AiConfig.resolveModel(rest);
        if (m == null) return say(c, "ai: modelo desconhecido. Use haiku, sonnet ou opus");

        AiConfig.setModel(c, m);
        return say(c, "ai: modelo = " + m);
    }

    private static String key(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) {
                return say(c, "ai: a área de transferência está vazia. Copie a chave no Console e rode ai -key de novo.");
            }

            CharSequence text = data.getItemAt(0).coerceToText(c);
            String k = text == null ? "" : text.toString().trim();

            // never print what was copied, only say whether it looks like a key
            if (!k.startsWith("sk-ant-") || k.length() < 20 || k.length() > 300 || k.matches(".*\\s.*")) {
                return say(c, "ai: o que está copiado não parece uma chave da Anthropic (sk-ant-...). Nada foi salvo.");
            }

            AiConfig.setKey(c, k);

            try {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                else cm.setPrimaryClip(ClipData.newPlainText("", ""));
            } catch (Exception ignored) {}

            return say(c, "ai: chave salva (" + AiConfig.keyHint(c) + ") e área de transferência limpa.");
        } catch (Exception e) {
            return say(c, "ai: não foi possível ler a área de transferência.");
        }
    }

    /** Prints a line in cyan. Returns null so the caller can "return say(...)" without printing twice. */
    private static String say(Context c, String text) {
        AiClient.info(c, text);
        return null;
    }

    @Override
    public int[] argType() {
        return new int[] {CommandAbstraction.PLAIN_TEXT};
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public int helpRes() {
        return R.string.help_ai;
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
