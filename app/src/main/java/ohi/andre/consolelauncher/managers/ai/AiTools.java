package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.managers.reminder.ReminderAction;
import ohi.andre.consolelauncher.managers.reminder.ReminderReceiver;
import ohi.andre.consolelauncher.managers.reminder.ReminderScheduler;
import ohi.andre.consolelauncher.managers.reminder.ReminderStore;

/**
 * The tools the model may call during "ai ...": reminders, the contact search and the memory.
 * Every tool runs on the phone and returns a short text. None of them sends a message, places a call
 * or opens anything: an action only happens later, when the user taps "Ir" on the reminder card.
 */
public class AiTools {

    private static final String WHEN_FORMAT = "yyyy-MM-dd HH:mm";

    /** Added to the system prompt of every request: the clock, the memory keys and the rules of the tools. */
    public static String systemExtra(Context c) {
        StringBuilder b = new StringBuilder();

        b.append("\n\nAgora: ").append(new SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", new Locale("pt", "BR")).format(new Date())).append('.');

        b.append("\n\nLembretes: use criar_lembrete quando o usuário pedir para ser lembrado de algo. ")
                .append("Pergunte só o que falta para o lembrete ser útil (hora, assunto, contato, texto da mensagem), ")
                .append("uma pergunta curta por vez, e não pergunte o que já foi dito ou está na memória. ")
                .append("Se o pedido já estiver completo e sem dúvida, crie direto, sem pedir confirmação. ")
                .append("Se você deduziu algo (contato, número, texto de mensagem), mostre o que vai criar e crie só depois do sim. ")
                .append("Se o lembrete envolve ligar, mandar mensagem, abrir um app, um link ou um lugar, preencha a ação para o botão Ir; ")
                .append("se não envolve, não preencha ação. ")
                .append("Mensagens são sempre por WhatsApp, nunca SMS. O usuário tem WhatsApp normal e WhatsApp Business: ")
                .append("pergunte qual usar, a não ser que a memória já diga. ")
                .append("Para achar um número, veja primeiro a memória e depois buscar_contato; se houver mais de um resultado, pergunte qual. ")
                .append("Nunca invente números. Você não envia mensagens nem faz ligações: isso acontece quando o usuário toca em Ir.");

        b.append("\n\nMemória: sempre que criar um lembrete que usou um contato, um número, um endereço ou uma escolha de app ")
                .append("(por exemplo qual WhatsApp) que ainda não está na memória, termine a resposta oferecendo guardar, em uma frase: ")
                .append("\"Quer que eu guarde [o dado] para a próxima vez?\". Só chame guardar_memoria depois do sim. ")
                .append("Faça o mesmo quando o usuário contar algo estável sobre ele (como prefere ser chamado, rotina, lugares). ")
                .append("Não ofereça para coisas passageiras ou lembretes simples sem contato. ")
                .append("Quando a memória já tiver o dado, use-o e apenas confirme, sem perguntar de novo. ");

        List<String> keys = AiMemory.keys(c);
        if (keys.isEmpty()) {
            b.append("A memória está vazia.");
        } else {
            b.append("Chaves guardadas (leia o valor com buscar_memoria): ");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(keys.get(i));
            }
            b.append('.');
        }

        return b.toString();
    }

    private static JSONObject prop(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws Exception {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);

        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("input_schema", new JSONObject().put("type", "object").put("properties", properties).put("required", req));
    }

    public static JSONArray definitions() throws Exception {
        JSONArray tools = new JSONArray();

        tools.put(tool("criar_lembrete",
                "Cria um lembrete que aparece como cartão na tela inicial na hora marcada.",
                new JSONObject()
                        .put("titulo", prop("string", "Título curto do lembrete"))
                        .put("detalhe", prop("string", "Detalhe opcional, uma ou duas frases"))
                        .put("quando", prop("string", "Data e hora locais no formato " + WHEN_FORMAT))
                        .put("acao_tipo", new JSONObject().put("type", "string")
                                .put("description", "Ação do botão Ir. Deixe sem preencher para um lembrete simples")
                                .put("enum", new JSONArray().put(ReminderAction.CALL).put(ReminderAction.WHATSAPP)
                                        .put(ReminderAction.APP).put(ReminderAction.LINK).put(ReminderAction.MAP)))
                        .put("acao_alvo", prop("string", "ligar e whatsapp: número com DDD. abrir_app: nome do app. abrir_link: URL. mapa: endereço ou lugar"))
                        .put("acao_texto", prop("string", "whatsapp: a mensagem que ficará escrita para o usuário enviar"))
                        .put("whatsapp_app", new JSONObject().put("type", "string")
                                .put("description", "whatsapp: qual dos dois apps usar")
                                .put("enum", new JSONArray().put(ReminderAction.WA_NORMAL).put(ReminderAction.WA_BUSINESS)))
                        .put("ligar_direto", prop("boolean", "ligar: true só se o usuário pediu para discar sem passar pelo discador")),
                "titulo", "quando"));

        tools.put(tool("listar_lembretes", "Lista os lembretes existentes, com número e hora.", new JSONObject()));

        tools.put(tool("remover_lembrete", "Remove um lembrete pelo número. Só use quando o usuário pedir.",
                new JSONObject().put("numero", prop("integer", "Número do lembrete")), "numero"));

        tools.put(tool("buscar_contato", "Procura um nome na agenda do celular e devolve nome e número dos que combinam.",
                new JSONObject().put("nome", prop("string", "Nome ou parte do nome")), "nome"));

        tools.put(tool("buscar_memoria", "Lê o valor guardado em uma chave da memória.",
                new JSONObject().put("chave", prop("string", "Uma das chaves guardadas")), "chave"));

        tools.put(tool("guardar_memoria", "Guarda um dado na memória. Só depois de o usuário dizer sim.",
                new JSONObject()
                        .put("chave", prop("string", "Nome curto, em minúsculas (ex.: contabilidade)"))
                        .put("valor", prop("string", "O dado, em uma linha (ex.: Fulana, 61 99999-0000, WhatsApp Business)")),
                "chave", "valor"));

        return tools;
    }

    public static String run(Context c, String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        if (name == null) name = "";

        switch (name) {
            case "criar_lembrete":
                return createReminder(c, in);
            case "listar_lembretes":
                return listReminders(c);
            case "remover_lembrete": {
                int id = in.optInt("numero", -1);
                if (!ReminderStore.remove(c, id)) return "erro: não existe o lembrete " + id;
                ReminderScheduler.cancel(c, id);
                ReminderReceiver.cancelNotification(c, id);
                AiClient.info(c, "lembrete #" + id + " removido");
                return "ok, removido";
            }
            case "buscar_contato":
                return ContactSearch.search(c, in.optString("nome"));
            case "buscar_memoria": {
                String v = AiMemory.get(c, in.optString("chave"));
                return v == null ? "nada guardado nessa chave" : v;
            }
            case "guardar_memoria": {
                String key = AiMemory.normalize(in.optString("chave"));
                String error = AiMemory.put(c, key, in.optString("valor"));
                if (error != null) return "erro: " + error;
                AiClient.info(c, "memória: guardado \"" + key + "\" (veja com ai -memory)");
                return "ok, guardado";
            }
            default:
                return "erro: ferramenta desconhecida";
        }
    }

    private static String createReminder(Context c, JSONObject in) {
        String title = in.optString("titulo").trim();
        if (title.length() == 0) return "erro: falta o título";

        long when;
        try {
            SimpleDateFormat f = new SimpleDateFormat(WHEN_FORMAT, Locale.US);
            f.setLenient(false);
            when = f.parse(in.optString("quando").trim()).getTime();
        } catch (Exception e) {
            return "erro: quando deve estar no formato " + WHEN_FORMAT;
        }
        if (when <= System.currentTimeMillis()) return "erro: esse horário já passou; confirme a data e a hora com o usuário";

        ReminderStore.Reminder action = null;
        String type = in.optString("acao_tipo").trim();
        if (type.length() > 0) {
            if (!ReminderAction.isType(type)) return "erro: acao_tipo inválido";

            String target = in.optString("acao_alvo").trim();
            if (target.length() == 0) return "erro: falta acao_alvo";

            action = new ReminderStore.Reminder();
            action.actionType = type;
            action.actionTarget = target;

            if (ReminderAction.CALL.equals(type)) {
                if (target.replaceAll("[^0-9]", "").length() < 3) return "erro: acao_alvo deve ser um número de telefone";
                action.actionDirect = in.optBoolean("ligar_direto", false);
            } else if (ReminderAction.WHATSAPP.equals(type)) {
                if (ReminderAction.whatsappNumber(target) == null) return "erro: o número precisa ter DDD; pergunte ao usuário";

                String app = in.optString("whatsapp_app").trim();
                if (!ReminderAction.WA_NORMAL.equals(app) && !ReminderAction.WA_BUSINESS.equals(app)) {
                    return "erro: falta whatsapp_app (normal ou business); pergunte ao usuário qual usar";
                }
                action.actionApp = app;
                action.actionText = in.optString("acao_texto").trim();
            } else if (ReminderAction.LINK.equals(type)) {
                if (!target.startsWith("https://") && !target.startsWith("http://")) return "erro: o link deve começar com https://";
            } else if (ReminderAction.APP.equals(type)) {
                if (ReminderAction.findApp(c, target) == null) return "erro: não há app instalado com o nome " + target;
            }
        }

        ReminderStore.Reminder r = ReminderStore.add(c, title, in.optString("detalhe"), when, ReminderStore.SOURCE_AI, action);
        if (r == null) return "erro: limite de " + ReminderStore.MAX_ITEMS + " lembretes atingido";

        ReminderScheduler.schedule(c, r);

        String line = "lembrete #" + r.id + " criado para " + ReminderStore.label(r.when) + ": " + r.title;
        if (r.hasAction()) line += "\n  Ir -> " + ReminderAction.describe(r);
        AiClient.info(c, line);

        return "ok, lembrete " + r.id + " criado para " + ReminderStore.label(r.when);
    }

    private static String listReminders(Context c) {
        List<ReminderStore.Reminder> all = ReminderStore.all(c);
        if (all.isEmpty()) return "nenhum lembrete";

        StringBuilder b = new StringBuilder();
        for (ReminderStore.Reminder r : all) {
            if (b.length() > 0) b.append('\n');
            b.append(r.id).append(" | ").append(ReminderStore.label(r.when)).append(" | ").append(r.title);
            if (r.hasAction()) b.append(" | Ir: ").append(ReminderAction.describe(r));
        }
        return b.toString();
    }
}
