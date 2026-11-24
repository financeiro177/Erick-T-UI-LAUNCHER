package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tools of Jarvis: his own memory, his mood, read-only access to the memory of "ai",
 * and the tools of "ai" that act on the phone (reminders, opening apps and links, contacts, help of the commands).
 */
public class JarvisTools {

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

    /** screen: whether the tools that read and touch the screen are offered (never in a turn started by a trigger). */
    public static JSONArray definitions(boolean screen) throws Exception {
        JSONArray tools = new JSONArray();

        if (screen) {
            tools.put(tool("ver_tela", "Lê a tela que está na frente agora: devolve os itens numerados (textos, botões, campos). "
                            + "Use antes de tocar e depois de cada toque, para ver o que mudou.",
                    new JSONObject()));
            tools.put(tool("tocar", "Toca em um item da última tela lida.",
                    new JSONObject().put("item", prop("integer", "Número do item, como veio em ver_tela")), "item"));
            tools.put(tool("digitar", "Escreve em um campo de texto da última tela lida (substitui o que houver).",
                    new JSONObject()
                            .put("item", prop("integer", "Número do campo"))
                            .put("texto", prop("string", "O que escrever")),
                    "item", "texto"));
            tools.put(tool("rolar", "Rola a tela.",
                    new JSONObject().put("direcao", prop("string", "baixo ou cima")), "direcao"));
            tools.put(tool("apertar", "Aperta um botão do sistema.",
                    new JSONObject().put("botao", prop("string", "voltar, inicio, recentes ou notificacoes")), "botao"));
            tools.put(tool("esperar", "Espera a tela carregar antes de ler de novo.",
                    new JSONObject().put("segundos", prop("integer", "De 1 a 5")), "segundos"));
        }

        tools.put(tool("buscar_no_app", "Abre um app já procurando ou tocando algo.",
                new JSONObject()
                        .put("app", prop("string", "youtube, ytmusic, spotify, maps, loja (Play Store) ou web"))
                        .put("texto", prop("string", "O que procurar")),
                "app", "texto"));
        tools.put(tool("whatsapp", "Abre a conversa do WhatsApp com a mensagem já escrita; é ele quem toca em enviar.",
                new JSONObject()
                        .put("numero", prop("string", "Telefone com DDD (e DDI se tiver); use buscar_contato para achar"))
                        .put("texto", prop("string", "A mensagem")),
                "numero", "texto"));
        tools.put(tool("discar", "Abre o discador com o número pronto; é ele quem toca em ligar.",
                new JSONObject().put("numero", prop("string", "Telefone")), "numero"));
        tools.put(tool("alarme", "Cria um alarme no relógio do celular.",
                new JSONObject()
                        .put("hora", prop("integer", "0 a 23"))
                        .put("minuto", prop("integer", "0 a 59"))
                        .put("rotulo", prop("string", "Nome do alarme")),
                "hora", "minuto"));
        tools.put(tool("timer", "Inicia uma contagem regressiva no relógio do celular.",
                new JSONObject()
                        .put("segundos", prop("integer", "Duração"))
                        .put("rotulo", prop("string", "Nome do timer")),
                "segundos"));
        tools.put(tool("evento", "Abre a agenda com um evento preenchido; é ele quem salva.",
                new JSONObject()
                        .put("titulo", prop("string", "Nome do evento"))
                        .put("inicio", prop("string", "yyyy-MM-dd HH:mm"))
                        .put("duracao_min", prop("integer", "Duração em minutos (padrão 60)")),
                "titulo", "inicio"));
        tools.put(tool("configuracao", "Abre uma tela das configurações do Android.",
                new JSONObject().put("tela", prop("string", "wifi, bluetooth, bateria, som, tela, apps, local, dados, notificacoes ou geral")),
                "tela"));

        tools.put(tool("criar_gatilho", "Marca um momento em que VOCÊ vai querer falar com ele. Quando disparar, aparece "
                        + "\"Jarvis quer falar com você\" e, se ele atender, você recebe o motivo e fala primeiro. Crie por conta própria.",
                new JSONObject()
                        .put("tipo", prop("string", "hora, ou um acontecimento: bateria_baixa, bateria_cheia, carregador_ligado, "
                                + "carregador_desligado, fone_conectado, wifi_caiu, wifi_voltou, primeiro_uso_do_dia"))
                        .put("quando", prop("string", "Só para tipo hora: HH:mm, yyyy-MM-dd HH:mm ou +minutos (ex.: +90)"))
                        .put("motivo", prop("string", "O que você quer dizer ou cobrar quando disparar"))
                        .put("diario", prop("boolean", "Só para hora no formato HH:mm: repetir todo dia")),
                "tipo", "motivo"));
        tools.put(tool("listar_gatilhos", "Lista os gatilhos que você criou.", new JSONObject()));
        tools.put(tool("remover_gatilho", "Apaga um gatilho seu.",
                new JSONObject().put("id", prop("integer", "Número do gatilho")), "id"));
        tools.put(tool("ficar_quieto", "Faz você parar de chamar por um tempo (por exemplo depois de levar bronca de madrugada).",
                new JSONObject().put("minutos", prop("integer", "Por quanto tempo; 0 volta ao normal")), "minutos"));

        tools.put(tool("jarvis_guardar", "Guarda um fato na sua memória (a do Jarvis). Use por conta própria.",
                new JSONObject()
                        .put("chave", prop("string", "Nome curto, em minúsculas (ex.: comida favorita)"))
                        .put("valor", prop("string", "O fato, em uma linha")),
                "chave", "valor"));

        tools.put(tool("jarvis_lembrar", "Lê o valor de uma chave da sua memória.",
                new JSONObject().put("chave", prop("string", "Uma das suas chaves")), "chave"));

        tools.put(tool("jarvis_esquecer", "Apaga uma chave da sua memória.",
                new JSONObject().put("chave", prop("string", "Uma das suas chaves")), "chave"));

        tools.put(tool("atualizar_relacao", "Reescreve o resumo de como anda a sua relação com ele (substitui o anterior).",
                new JSONObject().put("texto", prop("string", "Poucas linhas, no seu ponto de vista")), "texto"));

        // his mood is not a tool: he reports it in a mark at the end of every answer (see JarvisClient.MOOD_MARK),
        // which costs no extra request

        tools.put(tool("ler_memoria_ai", "Lê uma chave da memória do outro assistente (o do comando ai). Só leitura.",
                new JSONObject().put("chave", prop("string", "Uma das chaves do outro assistente")), "chave"));

        tools.put(tool("fechar_tela", "Fecha a sua tela e devolve o terminal do launcher. Use quando ele pedir para sair, "
                        + "fechar ou encerrar o modo Jarvis. Diga a sua despedida na mesma resposta; a tela fecha quando você terminar de falar.",
                new JSONObject()));

        // the tools of "ai" that act on the phone; its memory tools are replaced by the ones above
        JSONArray shared = AiTools.definitions();
        for (int i = 0; i < shared.length(); i++) {
            JSONObject t = shared.optJSONObject(i);
            if (t == null) continue;
            String name = t.optString("name");
            if ("guardar_memoria".equals(name) || "buscar_memoria".equals(name)) continue;
            tools.put(t);
        }

        // the last tool carries the cache mark: tools and the fixed part of the instructions are the same in every request
        if (tools.length() > 0) {
            tools.getJSONObject(tools.length() - 1).put("cache_control", new JSONObject().put("type", "ephemeral"));
        }

        return tools;
    }

    // tools run on the network thread, never on the main one, so waiting here blocks nothing on screen
    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    public static String run(Context c, String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        if (name == null) name = "";

        switch (name) {
            case "jarvis_guardar": {
                String error = JarvisMemory.put(c, in.optString("chave"), in.optString("valor"));
                return error == null ? "ok, guardado" : "erro: " + error;
            }
            case "jarvis_lembrar": {
                String v = JarvisMemory.get(c, in.optString("chave"));
                return v == null ? "nada guardado nessa chave" : v;
            }
            case "jarvis_esquecer":
                return JarvisMemory.remove(c, in.optString("chave")) ? "ok, esquecido" : "não havia essa chave";
            case "atualizar_relacao":
                JarvisMemory.setRelation(c, in.optString("texto"));
                return "ok";
            case "ajustar_humor": {
                if (!in.has("nivel")) return "erro: falta o nivel";
                JarvisMood.set(c, in.optInt("nivel", JarvisMood.get(c)), in.optString("motivo"));
                return "ok, nível agora " + JarvisMood.get(c);
            }
            case "fechar_tela":
                JarvisSession.exitRequested = true;
                return "ok, a tela fecha quando você terminar de falar";

            case "ver_tela": {
                JarvisAccess a = JarvisAccess.instance;
                return a == null ? JarvisAccess.OFF : a.dump();
            }
            case "tocar": {
                JarvisAccess a = JarvisAccess.instance;
                if (a == null) return JarvisAccess.OFF;
                String r = a.tap(in.optInt("item", -1));
                pause(700);
                return r;
            }
            case "digitar": {
                JarvisAccess a = JarvisAccess.instance;
                if (a == null) return JarvisAccess.OFF;
                return a.type(in.optInt("item", -1), in.optString("texto"));
            }
            case "rolar": {
                JarvisAccess a = JarvisAccess.instance;
                if (a == null) return JarvisAccess.OFF;
                String r = a.scroll(in.optString("direcao"));
                pause(500);
                return r;
            }
            case "apertar": {
                JarvisAccess a = JarvisAccess.instance;
                if (a == null) return JarvisAccess.OFF;
                String r = a.press(in.optString("botao"));
                pause(700);
                return r;
            }
            case "esperar":
                pause(Math.max(1, Math.min(5, in.optInt("segundos", 1))) * 1000L);
                return "ok";

            case "buscar_no_app":
                return JarvisPhone.search(c, in.optString("app"), in.optString("texto"));
            case "whatsapp":
                return JarvisPhone.whatsapp(c, in.optString("numero"), in.optString("texto"));
            case "discar":
                return JarvisPhone.dial(c, in.optString("numero"));
            case "alarme":
                return JarvisPhone.alarm(c, in.optInt("hora", -1), in.optInt("minuto", 0), in.optString("rotulo"));
            case "timer":
                return JarvisPhone.timer(c, in.optInt("segundos", 0), in.optString("rotulo"));
            case "evento":
                return JarvisPhone.event(c, in.optString("titulo"), in.optString("inicio"), in.optInt("duracao_min", 60));
            case "configuracao":
                return JarvisPhone.settings(c, in.optString("tela"));

            case "criar_gatilho":
                return JarvisTriggers.create(c, in.optString("tipo"), in.optString("quando"), in.optString("motivo"), in.optBoolean("diario"));
            case "listar_gatilhos": {
                java.util.List<JarvisTriggers.Trigger> list = JarvisTriggers.own(c);
                if (list.isEmpty()) return "você não criou nenhum gatilho";
                StringBuilder b = new StringBuilder();
                for (JarvisTriggers.Trigger t : list) b.append(t.id).append(". ").append(JarvisTriggers.describe(t)).append('\n');
                return b.toString().trim();
            }
            case "remover_gatilho":
                return JarvisTriggers.remove(c, in.optInt("id", -1)) ? "ok, removido" : "não existe gatilho com esse número";
            case "ficar_quieto":
                return JarvisTriggers.setQuiet(c, in.optInt("minutos", 0));
            case "ler_memoria_ai": {
                String v = AiMemory.get(c, in.optString("chave"));
                return v == null ? "nada guardado nessa chave" : v;
            }
            case "guardar_memoria":
            case "buscar_memoria":
                return "erro: use as suas ferramentas de memória";
            default:
                return AiTools.run(c, name, in);
        }
    }
}
