package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * The requests of "ai -jarvis". Unlike AiClient, the answer is streamed: each piece of text is handed to the
 * listener as soon as it arrives, so the screen can write it and the voice can start before the answer is complete.
 * Jarvis has his own conversation (not the one of "ai"), kept only in memory.
 * Never retries by itself and never shows the API key.
 */
public class JarvisClient {

    public interface Listener {
        /** A new piece of the answer. */
        void onText(String delta);

        /** The model asked for a tool (it runs right away). */
        void onTool(String name);

        /** The turn is over; "full" is everything that was said in it. */
        void onDone(String full);

        void onError(String message);
    }

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 500;
    private static final int MAX_STEPS = 5;

    private static final long INACTIVITY_MS = 30L * 60L * 1000L;
    private static final int HISTORY_MAX = 40;
    private static final int HISTORY_KEEP = 28;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** The mark that closes every answer with his new level of unwillingness: [h:72]. Never shown or spoken. */
    public static final java.util.regex.Pattern MOOD_MARK = java.util.regex.Pattern.compile("\\[\\s*h\\s*:\\s*(\\d{1,3})\\s*\\]");

    /** Removes the mark, complete or still arriving, from a text that will be shown or spoken. */
    public static String withoutMoodMark(String s) {
        return MOOD_MARK.matcher(s).replaceAll("").replaceAll("\\[\\s*(h\\s*(:\\s*\\d{0,3}\\s*)?)?$", "");
    }

    private static void readMood(Context c, String said) {
        java.util.regex.Matcher m = MOOD_MARK.matcher(said);
        int level = -1;
        while (m.find()) {
            try {
                level = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {}
        }
        if (level < 0 || level > 100) return;

        int before = JarvisMood.get(c);
        if (level != before) JarvisMood.set(c, level, level > before ? "a conversa o irritou" : "a conversa o acalmou");
    }

    private static final Object LOCK = new Object();
    private static final List<AiConversation.Message> history = new ArrayList<>();
    private static long lastActivity;
    private static long contextTokens;
    private static String modelOverride;

    private static boolean busy;
    private static int generation;
    private static Call current;

    // ---- conversation ----

    /** The cheapest model is the default here too; another one lasts until the conversation of Jarvis ends. */
    public static String model() {
        synchronized (LOCK) {
            return modelOverride != null ? modelOverride : AiConfig.defaultModel();
        }
    }

    public static void setModel(String model) {
        synchronized (LOCK) {
            modelOverride = AiConfig.defaultModel().equals(model) ? null : model;
        }
    }

    public static long contextTokens() {
        synchronized (LOCK) {
            return contextTokens;
        }
    }

    public static boolean isOpen() {
        synchronized (LOCK) {
            return !history.isEmpty();
        }
    }

    /** Ends the conversation (the memory of Jarvis is not touched) and brings the model back to the default. */
    public static void reset() {
        cancel();
        synchronized (LOCK) {
            history.clear();
            lastActivity = 0;
            contextTokens = 0;
            modelOverride = null;
        }
    }

    /** Drops the request in progress, if any. Its listener is not called anymore. */
    public static void cancel() {
        Call call;
        synchronized (LOCK) {
            generation++;
            busy = false;
            call = current;
            current = null;
        }
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {}
        }
    }

    private static boolean alive(int gen) {
        synchronized (LOCK) {
            return gen == generation;
        }
    }

    private static void finish(int gen) {
        synchronized (LOCK) {
            if (gen == generation) {
                busy = false;
                current = null;
            }
        }
    }

    private static class Turn {
        final List<AiConversation.Message> messages = new ArrayList<>();
        final StringBuilder said = new StringBuilder();
        long lastContext;
        int steps;
    }

    // ---- request ----

    public static void ask(Context context, String phrase, final Listener listener) {
        final Context c = context.getApplicationContext();

        final String key = AiConfig.key(c);
        if (key == null) {
            listener.onError("sem chave. No terminal: copie a chave no Console e use ai -key");
            return;
        }

        if (overLimit(c)) {
            listener.onError("limite do ciclo quase no fim (" + AiUsage.money(AiUsage.cycle(c).cost) + " de "
                    + AiUsage.money(AiConfig.limitUsd(c)) + "). Ajuste no terminal com ai -limite");
            return;
        }

        final int gen;
        synchronized (LOCK) {
            if (busy) {
                listener.onError("aguarde a resposta anterior");
                return;
            }
            busy = true;
            gen = ++generation;

            if (!history.isEmpty() && System.currentTimeMillis() - lastActivity > INACTIVITY_MS) {
                history.clear();
                contextTokens = 0;
                modelOverride = null;
            }
        }

        Turn turn = new Turn();
        turn.messages.add(new AiConversation.Message("user", phrase));
        send(c, key, model(), turn, listener, gen);
    }

    private static boolean overLimit(Context c) {
        double limit = AiConfig.limitUsd(c);
        return limit > 0 && AiUsage.cycle(c).cost >= limit * 0.95;
    }

    private static void send(final Context c, final String key, final String model, final Turn turn,
                             final Listener listener, final int gen) {
        String json;
        try {
            JSONArray messages = new JSONArray();
            synchronized (LOCK) {
                for (AiConversation.Message m : history) {
                    messages.put(new JSONObject().put("role", m.role).put("content", m.content));
                }
            }
            for (AiConversation.Message m : turn.messages) {
                messages.put(new JSONObject().put("role", m.role).put("content", m.content));
            }

            JSONArray system = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", JarvisPersona.stable(c))
                            .put("cache_control", new JSONObject().put("type", "ephemeral")))
                    .put(new JSONObject().put("type", "text").put("text", JarvisPersona.dynamic(c)));

            json = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("stream", true)
                    .put("system", system)
                    .put("tools", JarvisTools.definitions())
                    .put("messages", messages)
                    .toString();
        } catch (Exception e) {
            finish(gen);
            listener.onError("não foi possível montar o pedido");
            return;
        }

        Request request = new Request.Builder()
                .url(URL)
                .header("x-api-key", key)
                .header("anthropic-version", VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(json, JSON))
                .build();

        turn.steps++;

        Call call = CLIENT.newCall(request);
        synchronized (LOCK) {
            if (gen != generation) return;
            current = call;
        }

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!alive(gen)) return;
                finish(gen);
                listener.onError(e instanceof InterruptedIOException ? "tempo esgotado" : "sem conexão");
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean goesOn = false;
                try {
                    if (response.code() == 200 && response.body() != null) {
                        goesOn = stream(c, key, model, turn, listener, gen, response.body().source());
                    } else {
                        String body = response.body() != null ? response.body().string() : "";
                        if (alive(gen)) listener.onError(errorText(c, key, response.code(), body));
                    }
                } catch (Exception e) {
                    if (alive(gen)) listener.onError("a resposta foi interrompida");
                } finally {
                    try {
                        response.close();
                    } catch (Exception ignored) {}
                    if (!goesOn) finish(gen);
                }
            }
        });
    }

    /** Reads the stream of one request. Returns true when tools ran and another request was sent. */
    private static boolean stream(Context c, String key, String model, Turn turn, Listener listener, int gen,
                                  BufferedSource source) throws Exception {
        JSONArray content = new JSONArray();
        JSONArray toolResults = new JSONArray();

        StringBuilder text = null;
        StringBuilder toolJson = null;
        String toolId = null, toolName = null;

        String stopReason = "";
        long in = 0, cacheWrite = 0, cacheRead = 0, out = 0;
        String streamError = null;

        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (!alive(gen)) return false;
            if (!line.startsWith("data:")) continue;

            String data = line.substring(5).trim();
            if (data.length() == 0 || data.charAt(0) != '{') continue;

            JSONObject ev = new JSONObject(data);
            String type = ev.optString("type");

            if ("message_start".equals(type)) {
                JSONObject msg = ev.optJSONObject("message");
                JSONObject usage = msg == null ? null : msg.optJSONObject("usage");
                if (usage != null) {
                    in = usage.optLong("input_tokens");
                    cacheWrite = usage.optLong("cache_creation_input_tokens");
                    cacheRead = usage.optLong("cache_read_input_tokens");
                    out = usage.optLong("output_tokens");
                }
            } else if ("content_block_start".equals(type)) {
                JSONObject block = ev.optJSONObject("content_block");
                String blockType = block == null ? "" : block.optString("type");
                text = null;
                toolJson = null;
                if ("text".equals(blockType)) {
                    text = new StringBuilder(block.optString("text"));
                } else if ("tool_use".equals(blockType)) {
                    toolJson = new StringBuilder();
                    toolId = block.optString("id");
                    toolName = block.optString("name");
                }
            } else if ("content_block_delta".equals(type)) {
                JSONObject delta = ev.optJSONObject("delta");
                String deltaType = delta == null ? "" : delta.optString("type");
                if ("text_delta".equals(deltaType) && text != null) {
                    String piece = delta.optString("text");
                    if (piece.length() > 0) {
                        text.append(piece);
                        turn.said.append(piece);
                        listener.onText(piece);
                    }
                } else if ("input_json_delta".equals(deltaType) && toolJson != null) {
                    toolJson.append(delta.optString("partial_json"));
                }
            } else if ("content_block_stop".equals(type)) {
                if (text != null) {
                    if (text.toString().trim().length() > 0) {
                        content.put(new JSONObject().put("type", "text").put("text", text.toString()));
                    }
                    text = null;
                } else if (toolJson != null) {
                    JSONObject input;
                    try {
                        input = toolJson.length() == 0 ? new JSONObject() : new JSONObject(toolJson.toString());
                    } catch (Exception e) {
                        input = new JSONObject();
                    }
                    content.put(new JSONObject().put("type", "tool_use").put("id", toolId).put("name", toolName).put("input", input));

                    listener.onTool(toolName);
                    String result;
                    try {
                        result = JarvisTools.run(c, toolName, input);
                    } catch (Exception e) {
                        result = "erro: a ferramenta falhou";
                    }
                    toolResults.put(new JSONObject().put("type", "tool_result").put("tool_use_id", toolId).put("content", result));
                    toolJson = null;
                }
            } else if ("message_delta".equals(type)) {
                JSONObject delta = ev.optJSONObject("delta");
                if (delta != null && !delta.isNull("stop_reason")) stopReason = delta.optString("stop_reason");
                JSONObject usage = ev.optJSONObject("usage");
                if (usage != null && usage.has("output_tokens")) out = usage.optLong("output_tokens");
            } else if ("error".equals(type)) {
                JSONObject err = ev.optJSONObject("error");
                streamError = err == null ? "erro" : err.optString("message", "erro");
                break;
            } else if ("message_stop".equals(type)) {
                break;
            }
        }

        if (!alive(gen)) return false;

        // cache writes cost a quarter more than normal input, cache reads a tenth
        AiUsage.record(c, model, Math.round(in + cacheWrite * 1.25 + cacheRead * 0.1), out);
        turn.lastContext = in + cacheWrite + cacheRead;

        if (streamError != null) {
            if (key != null) streamError = streamError.replace(key, "***");
            listener.onError(streamError.toLowerCase(Locale.ROOT).contains("overloaded")
                    ? "a API está sobrecarregada, tente de novo" : shorten(streamError));
            return false;
        }

        if ("tool_use".equals(stopReason) && toolResults.length() > 0) {
            turn.messages.add(new AiConversation.Message("assistant", content));
            turn.messages.add(new AiConversation.Message("user", toolResults));

            if (turn.steps < MAX_STEPS && !overLimit(c)) {
                // a space keeps the words of two steps apart when they are spoken
                if (turn.said.length() > 0) {
                    turn.said.append(' ');
                    listener.onText(" ");
                }
                send(c, key, model, turn, listener, gen);
                return true;
            }

            turn.messages.add(new AiConversation.Message("assistant", "(parei aqui)"));
            commit(turn);
            readMood(c, turn.said.toString());
            listener.onDone(turn.said.toString().trim());
            return false;
        }

        String said = turn.said.toString().trim();
        if (content.length() == 0) {
            if (turn.steps == 1) {
                listener.onError("a API respondeu sem texto");
                return false;
            }
            turn.messages.add(new AiConversation.Message("assistant", "(feito)"));
        } else {
            turn.messages.add(new AiConversation.Message("assistant", content));
        }

        commit(turn);
        readMood(c, said);
        listener.onDone(said);
        return false;
    }

    private static void commit(Turn turn) {
        synchronized (LOCK) {
            history.addAll(turn.messages);
            lastActivity = System.currentTimeMillis();
            contextTokens = turn.lastContext;

            // old turns leave from the front, always cutting where a spoken user message begins
            if (history.size() > HISTORY_MAX) {
                int cut = history.size() - HISTORY_KEEP;
                while (cut < history.size()) {
                    AiConversation.Message m = history.get(cut);
                    if ("user".equals(m.role) && m.content instanceof String) break;
                    cut++;
                }
                if (cut < history.size()) history.subList(0, cut).clear();
            }
        }
    }

    private static String shorten(String message) {
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private static String errorText(Context c, String key, int code, String body) {
        String message = "";
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) message = err.optString("message");
        } catch (Exception ignored) {}

        if (key != null && key.length() > 0) message = message.replace(key, "***");
        String lower = message.toLowerCase(Locale.ROOT);

        if (code == 401) return "chave recusada. No terminal: gere outra no Console e use ai -key";
        if (code == 402 || lower.contains("credit balance") || lower.contains("billing")) {
            return "o crédito de API acabou. Volta no próximo ciclo ("
                    + AiUsage.formatDate(AiUsage.nextRenewal(Calendar.getInstance(), AiConfig.cycleDay(c))) + ")";
        }
        if (code == 429) return "muitas chamadas seguidas, espere um pouco";
        if (code >= 500) return "a API falhou ou está sobrecarregada (" + code + ")";
        return "erro " + code + (message.length() > 0 ? ": " + shorten(message) : "");
    }
}
