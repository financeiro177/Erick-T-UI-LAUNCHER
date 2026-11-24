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

    public static JSONArray definitions() throws Exception {
        JSONArray tools = new JSONArray();

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

        tools.put(tool("ajustar_humor", "Muda o seu nível de má vontade.",
                new JSONObject()
                        .put("nivel", prop("integer", "De 0 (prestativo) a 100 (ódio computacional)"))
                        .put("motivo", prop("string", "Por que mudou, em poucas palavras")),
                "nivel", "motivo"));

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
                JarvisActivity.exitRequested = true;
                return "ok, a tela fecha quando você terminar de falar";
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
