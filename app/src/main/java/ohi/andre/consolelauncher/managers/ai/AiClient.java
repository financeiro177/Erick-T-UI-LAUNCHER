package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
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
    private static final int MAX_TOKENS = 400;

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

        String json;
        try {
            JSONArray messages = new JSONArray();
            List<AiConversation.Message> history = AiConversation.snapshot();
            for (AiConversation.Message m : history) {
                messages.put(new JSONObject().put("role", m.role).put("content", m.text));
            }
            messages.put(new JSONObject().put("role", "user").put("content", phrase));

            json = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", SYSTEM)
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

        info(c, "...");

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                BUSY.set(false);
                if (e instanceof InterruptedIOException) warn(c, "ai: tempo esgotado. Tente de novo.");
                else warn(c, "ai: sem conexão.");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.code() == 200) handleSuccess(c, model, phrase, body);
                    else handleError(c, key, response.code(), body);
                } catch (Exception e) {
                    warn(c, "ai: resposta inesperada da API.");
                } finally {
                    try {
                        response.close();
                    } catch (Exception ignored) {}
                    BUSY.set(false);
                }
            }
        });
    }

    private static void handleSuccess(Context c, String model, String phrase, String body) throws Exception {
        JSONObject root = new JSONObject(body);

        StringBuilder text = new StringBuilder();
        JSONArray content = root.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) text.append(block.optString("text"));
            }
        }

        String answer = text.toString().trim();
        if (answer.length() == 0) {
            warn(c, "ai: a API respondeu sem texto.");
            return;
        }

        JSONObject usage = root.optJSONObject("usage");
        long in = usage == null ? 0 : usage.optLong("input_tokens");
        long out = usage == null ? 0 : usage.optLong("output_tokens");

        AiConversation.addExchange(phrase, answer, in, out, System.currentTimeMillis());
        AiUsage.record(c, model, in, out);

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
