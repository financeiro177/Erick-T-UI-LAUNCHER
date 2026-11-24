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
import ohi.andre.consolelauncher.managers.ai.AiMemory;
import ohi.andre.consolelauncher.managers.ai.AiUsage;
import ohi.andre.consolelauncher.managers.ai.JarvisClient;
import ohi.andre.consolelauncher.managers.ai.JarvisMemory;
import ohi.andre.consolelauncher.managers.ai.JarvisMood;
import ohi.andre.consolelauncher.managers.ai.JarvisPersona;
import ohi.andre.consolelauncher.managers.ai.JarvisVoice;

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
 *   ai -memory               what the assistant keeps (-rm key forgets one item)
 *   ai -key                  saves the key that is in the clipboard, then clears the clipboard
 *   ai -jarvis               opens the voice assistant (its own screen, personality and memory)
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

        // an option typed without the "-" works the same: "ai custo", "ai limite 50", "ai memory rm chave".
        // Only when the line is nothing but the option (and its value), so a real sentence is never mistaken for one.
        if (!line.startsWith("-")) {
            String[] words = line.split("\\s+");
            String first = words[0].toLowerCase(Locale.ROOT);
            boolean alone = words.length == 1 && FORGOTTEN_DASH.contains(" " + first + " ");
            boolean withValue = words.length == 2 && " limite ciclo aviso model ".contains(" " + first + " ")
                    && (words[1].matches("[0-9.,]+") || AiConfig.resolveModel(words[1]) != null);
            boolean memoryRm = words.length >= 3 && (first.equals("memory") || first.equals("memoria"))
                    && words[1].replace("-", "").equalsIgnoreCase("rm");
            if (alone || withValue || memoryRm) line = "-" + line;
        }

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
                case "-memory":
                case "-memoria":
                    return memory(c, rest);
                case "-jarvis":
                    return jarvis(c, rest);
                default:
                    // "-word" is taken as a mistyped option; anything else (e.g. "-5 + 3") is a normal phrase
                    if (opt.matches("-[a-zà-ú]+")) return say(c, "ai: opção desconhecida (" + opt + "). Digite ai para ver a ajuda.");
            }
        }

        if (line.toLowerCase(Locale.ROOT).matches("jarvis(\\s.*)?")) {
            String[] words = line.split("\\s+");
            if (words.length == 1 || JARVIS_OPTIONS.contains(" " + words[1].toLowerCase(Locale.ROOT).replace("-", "") + " ")) {
                return jarvis(c, line.substring(words[0].length()).trim());
            }
        }

        return chat(c, line);
    }

    private static final String FORGOTTEN_DASH = " custo fim novo limite ciclo aviso model memory memoria key ";

    private static final String JARVIS_OPTIONS = " voz memory memoria humor model modelo persona fim ";

    // ---- jarvis: the voice assistant, a full screen of its own ----

    /**
     * ai -jarvis                     opens the voice screen
     * ai -jarvis -voz ...            voice of the answers
     * ai -jarvis -memory [-rm key]   what Jarvis keeps about the user (-memory -limpar erases all)
     * ai -jarvis -humor [0..100]     his unwillingness
     * ai -jarvis -model [name]       model, only while his conversation lasts
     * ai -jarvis -persona [-rm]      the personality text, read from the clipboard
     * ai -jarvis -fim                ends his conversation (the memory stays)
     * The inner options also work without the "-".
     */
    private static String jarvis(Context c, String rest) {
        if (rest.length() == 0) {
            try {
                ohi.andre.consolelauncher.managers.ai.JarvisActivity.open(c);
            } catch (Exception e) {
                return say(c, "jarvis: não foi possível abrir a tela.");
            }
            return null;
        }

        String[] parts = rest.split("\\s+", 2);
        String opt = parts[0].toLowerCase(Locale.ROOT).replace("-", "");
        String arg = parts.length > 1 ? parts[1].trim() : "";
        String argLower = arg.toLowerCase(Locale.ROOT);

        switch (opt) {
            case "voz": {
                if (arg.length() == 0) {
                    JarvisVoice.list(c);
                    return null;
                }
                // the inner options take a "-" like every option, and also work without it
                String sub = argLower.startsWith("-") ? argLower.substring(1) : argLower;
                String subArg = arg.startsWith("-") ? arg.substring(1) : arg;

                if (sub.equals("teste")) {
                    JarvisVoice.test(c);
                    return null;
                }
                if (sub.equals("padrao") || sub.equals("padrão")) return say(c, JarvisVoice.useDefault(c));
                if (sub.startsWith("velocidade")) return say(c, JarvisVoice.setRate(c, subArg.substring(10).trim(), false));
                if (sub.startsWith("tom")) return say(c, JarvisVoice.setRate(c, subArg.substring(3).trim(), true));
                try {
                    return say(c, JarvisVoice.choose(c, Integer.parseInt(subArg.trim())));
                } catch (NumberFormatException e) {
                    return say(c, "jarvis: use ai -jarvis -voz para ver a lista e ai -jarvis -voz [número] para escolher.");
                }
            }
            case "memory":
            case "memoria": {
                if (argLower.startsWith("-rm") || argLower.startsWith("rm ") || argLower.equals("rm")) {
                    String k = arg.substring(argLower.startsWith("-rm") ? 3 : 2).trim();
                    if (k.length() == 0) return say(c, "jarvis: informe a chave. Exemplo: ai -jarvis -memory -rm comida favorita");
                    return say(c, JarvisMemory.remove(c, k) ? "jarvis: esquecido: " + JarvisMemory.normalize(k) : "jarvis: não há essa chave.");
                }
                if (argLower.equals("-limpar") || argLower.equals("limpar")) {
                    JarvisMemory.clear(c);
                    return say(c, "jarvis: memória e resumo da relação apagados. Ele volta a não te conhecer.");
                }

                java.util.List<String> keys = JarvisMemory.keys(c);
                StringBuilder b = new StringBuilder("== jarvis: memória (" + keys.size() + " de " + JarvisMemory.MAX_ITEMS + ") ==");
                for (String k : keys) b.append('\n').append(k).append(" = ").append(JarvisMemory.get(c, k));
                if (keys.isEmpty()) b.append("\n(vazia: ele ainda não guardou nada sobre você)");
                String relation = JarvisMemory.relation(c);
                b.append("\n\nrelação, nas palavras dele:\n").append(relation.length() == 0 ? "(ainda não escreveu nada)" : relation);
                b.append("\n\n(esquecer um item: ai -jarvis -memory -rm chave; apagar tudo: ai -jarvis -memory -limpar)");
                return say(c, b.toString());
            }
            case "humor": {
                if (arg.length() > 0) {
                    try {
                        int v = Integer.parseInt(arg.replace("%", "").trim());
                        if (v < 0 || v > 100) throw new NumberFormatException();
                        JarvisMood.set(c, v, "o usuário mexeu no humor dele à força");
                    } catch (NumberFormatException e) {
                        return say(c, "jarvis: use um número de 0 a 100. Exemplo: ai -jarvis -humor 20");
                    }
                }
                int mood = JarvisMood.get(c);
                String why = JarvisMood.why(c);
                return say(c, "jarvis: má vontade em " + mood + "% (" + JarvisMood.label(mood) + "); o normal de hoje é "
                        + JarvisMood.baseline() + "%." + (why.length() > 0 ? "\núltimo motivo: " + why : "")
                        + "\nEle mesmo muda isso durante a conversa, e o valor volta devagar ao normal.");
            }
            case "model":
            case "modelo": {
                if (arg.length() == 0) {
                    return say(c, "jarvis: modelo = " + JarvisClient.model().replace("claude-", "")
                            + "\nTrocar: ai -jarvis -model sonnet (vale até a conversa dele acabar; depois volta ao haiku)");
                }
                String m = AiConfig.resolveModel(arg);
                if (m == null) return say(c, "jarvis: modelo desconhecido. Use haiku, sonnet ou opus.");
                JarvisClient.setModel(m);
                return say(c, m.equals(AiConfig.defaultModel()) ? "jarvis: modelo = " + m + " (o padrão)"
                        : "jarvis: modelo = " + m + " só nesta conversa dele; ao encerrar volta ao haiku");
            }
            case "persona": {
                if (argLower.equals("-rm") || argLower.equals("rm")) {
                    return say(c, JarvisPersona.remove(c) ? "jarvis: personalidade apagada deste celular (volta a padrão)."
                            : "jarvis: não havia personalidade guardada.");
                }
                if (argLower.equals("-status") || argLower.equals("status")) return say(c, JarvisPersona.status(c));
                return persona(c);
            }
            case "fim":
                JarvisClient.reset();
                return say(c, "jarvis: conversa encerrada. A memória dele continua.");
            default:
                return say(c, "jarvis: opção desconhecida. Use: ai -jarvis (abre), -voz, -memory, -humor, -model, -persona, -fim");
        }
    }

    // reads the personality from the clipboard, so the text never passes through the command line or the screen
    private static String persona(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = cm == null ? null : cm.getPrimaryClip();
            CharSequence text = data == null || data.getItemCount() == 0 ? null : data.getItemAt(0).coerceToText(c);
            String t = text == null ? "" : text.toString().trim();

            if (t.length() < JarvisPersona.MIN_CHARS) return say(c, JarvisPersona.status(c)
                    + "\n(o que está copiado agora é curto demais para ser uma personalidade; nada mudou)");
            if (t.startsWith("sk-ant-")) return say(c, "jarvis: isso parece a chave da IA, não a personalidade. Nada foi salvo.");

            String error = JarvisPersona.importText(c, t);
            if (error != null) return say(c, "jarvis: " + error + ". Nada foi salvo.");

            JarvisClient.reset();
            return say(c, "jarvis: personalidade salva (" + t.length() + " letras), só neste celular. Vale a partir da próxima conversa.");
        } catch (Exception e) {
            return say(c, "jarvis: não foi possível ler a área de transferência.");
        }
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
        if (rest.length() == 0) return say(c, AiConfig.modelList(c));

        String m = AiConfig.resolveModel(rest);
        if (m == null) return say(c, "ai: modelo desconhecido.\n" + AiConfig.modelList(c));

        AiConfig.setModel(c, m);
        return say(c, m.equals(AiConfig.defaultModel()) ? "ai: modelo = " + m + " (o padrão)"
                : "ai: modelo = " + m + " só nesta conversa; ao encerrar volta ao " + AiConfig.defaultModel().replace("claude-", ""));
    }

    // "ai -memory" lists what the assistant keeps; "ai -memory -rm chave" forgets one item
    private static String memory(Context c, String rest) {
        String restLower = rest.toLowerCase(Locale.ROOT);
        if (restLower.startsWith("-rm") || restLower.startsWith("rm ") || restLower.equals("rm")) {
            String k = rest.substring(restLower.startsWith("-rm") ? 3 : 2).trim();
            if (k.length() == 0) return say(c, "ai: informe a chave. Exemplo: ai -memory -rm contabilidade");
            return say(c, AiMemory.remove(c, k) ? "ai: esquecido: " + AiMemory.normalize(k) : "ai: não há essa chave na memória.");
        }

        java.util.List<String> keys = AiMemory.keys(c);
        if (keys.isEmpty()) return say(c, "ai: a memória está vazia. Ela só guarda algo quando você aceita a sugestão.");

        StringBuilder b = new StringBuilder("== ai: memória (" + keys.size() + " de " + AiMemory.MAX_ITEMS + ") ==");
        for (String k : keys) b.append('\n').append(k).append(" = ").append(AiMemory.get(c, k));
        b.append("\n(para esquecer: ai -memory -rm chave)");
        return say(c, b.toString());
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
