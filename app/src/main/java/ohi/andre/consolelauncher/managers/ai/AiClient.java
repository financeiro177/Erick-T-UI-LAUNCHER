package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ohi.andre.consolelauncher.tuils.Tuils;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * One request to the Claude Messages API. Network work runs on OkHttp's own threads;
 * the answer (or a warning) is printed in the terminal with Tuils.sendOutput.
 * Never retries by itself (a retry would spend credit) and never prints the API key.
 */
public class AiClient {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final String VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 600;

    // requests allowed in one turn (the first one plus the ones that follow tool calls)
    private static final int MAX_STEPS = 5;

    private static final String SYSTEM = "Você é o assistente do T-UI, um launcher de terminal para Android. "
            + "Responda em português, de forma curta e direta, em texto simples (sem markdown, sem tabelas).";

    public static final int INFO = Color.CYAN;
    public static final int WARN = Color.YELLOW;

    // own client: does not change the timeouts of the one used by the rest of the launcher
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    public static void info(Context c, String text) {
        Tuils.sendOutput(INFO, c.getApplicationContext(), text);
    }

    public static void warn(Context c, String text) {
        Tuils.sendOutput(WARN, c.getApplicationContext(), text);
    }

    private static String renewal(Context c) {
        return AiUsage.formatDate(AiUsage.nextRenewal(Calendar.getInstance(), AiConfig.cycleDay(c)));
    }

    /** Sends "phrase" (plus the open conversation) and prints the answer when it arrives. */
    public static void ask(Context context, final String phrase) {
        final Context c = context.getApplicationContext();

        final String key = AiConfig.key(c);
        if (key == null) {
            warn(c, "ai: sem chave. Copie a chave no Console e use: ai -key");
            return;
        }

        double limit = AiConfig.limitUsd(c);
        double spent = AiUsage.cycle(c).cost;
        if (limit > 0 && spent >= limit * 0.95) {
            warn(c, "ai: limite do ciclo quase no fim (" + AiUsage.money(spent) + " de " + AiUsage.money(limit)
                    + "). Renova em " + renewal(c) + ". Ajuste com: ai -limite");
            return;
        }

        if (!BUSY.compareAndSet(false, true)) {
            warn(c, "ai: aguarde a resposta anterior.");
            return;
        }

        final String model = AiConfig.model(c);

        Turn turn = new Turn();
        turn.messages.add(new AiConversation.Message("user", phrase));

        info(c, "...");
        send(c, key, model, turn);
    }

    /** What one "ai ..." line adds to the conversation, and what it has cost so far. */
    private static class Turn {
        final List<AiConversation.Message> messages = new ArrayList<>();
        long in, out, lastIn;
        int steps;
    }

    private static void send(final Context c, final String key, final String model, final Turn turn) {
        String json;
        try {
            JSONArray messages = new JSONArray();
            for (AiConversation.Message m : AiConversation.snapshot()) {
                messages.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            for (AiConversation.Message m : turn.messages) {
                messages.put(new JSONObject().put("role", m.role).put("content", m.content));
            }

            json = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", SYSTEM + AiTools.systemExtra(c))
                    .put("tools", AiTools.definitions())
                    .put("messages", messages)
                    .toString();
        } catch (Exception e) {
            BUSY.set(false);
            warn(c, "ai: não foi possível montar o pedido.");
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

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                BUSY.set(false);
                if (e instanceof InterruptedIOException) warn(c, "ai: tempo esgotado. Tente de novo.");
                else warn(c, "ai: sem conexão.");
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean goesOn = false;
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.code() == 200) goesOn = handleSuccess(c, key, model, turn, body);
                    else handleError(c, key, response.code(), body);
                } catch (Exception e) {
                    warn(c, "ai: resposta inesperada da API.");
                } finally {
                    try {
                        response.close();
                    } catch (Exception ignored) {}
                    if (!goesOn) BUSY.set(false);
                }
            }
        });
    }

    /** Returns true when the model asked for tools and another request was sent (the turn is not over). */
    private static boolean handleSuccess(Context c, String key, String model, Turn turn, String body) throws Exception {
        JSONObject root = new JSONObject(body);

        JSONObject usage = root.optJSONObject("usage");
        long in = usage == null ? 0 : usage.optLong("input_tokens");
        long out = usage == null ? 0 : usage.optLong("output_tokens");
        turn.in += in;
        turn.out += out;
        turn.lastIn = in;
        AiUsage.record(c, model, in, out);

        StringBuilder text = new StringBuilder();
        JSONArray toolResults = new JSONArray();
        JSONArray content = root.optJSONArray("content");
        if (content == null) content = new JSONArray();

        boolean wantsTools = "tool_use".equals(root.optString("stop_reason"));

        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;

            String type = block.optString("type");
            if ("text".equals(type)) {
                text.append(block.optString("text"));
            } else if ("tool_use".equals(type) && wantsTools) {
                String result;
                try {
                    result = AiTools.run(c, block.optString("name"), block.optJSONObject("input"));
                } catch (Exception e) {
                    result = "erro: a ferramenta falhou";
                }
                toolResults.put(new JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.optString("id"))
                        .put("content", result));
            }
        }

        String answer = text.toString().trim();

        if (wantsTools && toolResults.length() > 0) {
            if (answer.length() > 0) Tuils.sendOutput(Integer.MAX_VALUE, c, answer);

            turn.messages.add(new AiConversation.Message("assistant", content));
            turn.messages.add(new AiConversation.Message("user", toolResults));

            if (turn.steps < MAX_STEPS && !overLimit(c)) {
                send(c, key, model, turn);
                return true;
            }

            // out of steps: the conversation must still end with an answer of the assistant
            turn.messages.add(new AiConversation.Message("assistant", "(parei aqui)"));
            AiConversation.commit(turn.messages, turn.in, turn.out, turn.lastIn, System.currentTimeMillis());
            warn(c, "ai: parei depois de " + turn.steps + " passos. Diga como continuar.");
            return false;
        }

        if (answer.length() == 0) {
            if (turn.steps == 1) {
                warn(c, "ai: a API respondeu sem texto.");
                return false;
            }
            answer = "feito.";
        }

        turn.messages.add(new AiConversation.Message("assistant", answer));
        AiConversation.commit(turn.messages, turn.in, turn.out, turn.lastIn, System.currentTimeMillis());

        Tuils.sendOutput(Integer.MAX_VALUE, c, answer);

        if ("max_tokens".equals(root.optString("stop_reason"))) {
            info(c, "(resposta cortada pelo limite de tamanho; peça para continuar)");
        }

        if (AiUsage.shouldWarnEighty(c)) {
            warn(c, "ai: 80% do limite do ciclo usado (" + AiUsage.money(AiUsage.cycle(c).cost) + " de "
                    + AiUsage.money(AiConfig.limitUsd(c)) + "). Renova em " + renewal(c) + ".");
        }

        if (AiConversation.shouldWarnSize(AiConfig.warnTokens(c))) {
            warn(c, "ai: esta conversa já tem ~" + AiConversation.contextTokens()
                    + " tokens. Use ai -novo ou ai -fim se quiser recomeçar.");
        }
        return false;
    }

    private static boolean overLimit(Context c) {
        double limit = AiConfig.limitUsd(c);
        return limit > 0 && AiUsage.cycle(c).cost >= limit * 0.95;
    }

    private static void handleError(Context c, String key, int code, String body) {
        String message = "";
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) message = err.optString("message");
        } catch (Exception ignored) {}

        // never echo the key, even if some message contained it
        if (key != null && key.length() > 0) message = message.replace(key, "***");

        String lower = message.toLowerCase(Locale.ROOT);

        if (code == 401) {
            warn(c, "ai: chave recusada. Gere outra no Console e use: ai -key");
        } else if (code == 402 || lower.contains("credit balance") || lower.contains("billing")) {
            warn(c, "ai: o crédito de API acabou. Volta no próximo ciclo (" + renewal(c)
                    + "). Confira em Console > Cobrança.");
        } else if (code == 429) {
            warn(c, "ai: muitas chamadas seguidas. Espere um pouco.");
        } else if (code >= 500) {
            warn(c, "ai: a API falhou ou está sobrecarregada (" + code + "). Tente de novo em instantes.");
        } else {
            if (message.length() > 160) message = message.substring(0, 160) + "...";
            warn(c, "ai: erro " + code + (message.length() > 0 ? ": " + message : "."));
        }
    }
}
