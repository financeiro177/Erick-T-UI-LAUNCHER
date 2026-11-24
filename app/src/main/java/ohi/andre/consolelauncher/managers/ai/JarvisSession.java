package ohi.andre.consolelauncher.managers.ai;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One conversation with Jarvis, with no screen of its own: it listens, sends to the model, and writes and speaks
 * the answer. Whoever shows it (the full screen or the bubble over other apps) attaches as its Ui; when the user
 * leaves the full screen with the bubble on, the conversation simply goes on in the bubble.
 *
 * The microphone is never on by itself. A tap turns it on; after an answer it listens once more, so that a
 * conversation can go on, and a silence turns it off again. Everything here runs on the main thread.
 */
public class JarvisSession {

    public static final int BOOT = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, STANDBY = 4;

    public interface Ui {
        void onState(int state);

        void onUser(String text, boolean complete);

        void onAnswer(String text);

        void onNote(String text, boolean warn);

        void onLevel(float level);

        void onNeedMic();

        void onData();

        void onExit();
    }

    private static final int MAX_SILENT_ROUNDS = 1;

    private static JarvisSession instance;

    /** Set by the tool Jarvis uses to close his own screen; obeyed when he finishes talking. */
    public static volatile boolean exitRequested;

    public static synchronized JarvisSession get(Context c) {
        AiConfig.load(c.getApplicationContext());
        if (instance == null) instance = new JarvisSession(c.getApplicationContext());
        return instance;
    }

    public static synchronized JarvisSession peek() {
        return instance;
    }

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;

    private SpeechRecognizer recognizer;
    private JarvisVoice voice;

    private int state = BOOT;
    private boolean voiceOk, listening, closed;
    private int silentRounds, turnId;

    private String network = "ok", userText = "", noteText = "";
    private boolean noteWarn;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();

    private JarvisSession(Context context) {
        c = context;
        exitRequested = false;

        voice = new JarvisVoice(c, new JarvisVoice.Listener() {
            @Override
            public void onReady(boolean ok) {
                voiceOk = ok;
                if (closed || state != BOOT) return;
                if (!AiConfig.hasKey(c)) {
                    note("Sem chave da IA. Saia e use no terminal: ai -key", true);
                } else if (!ok) {
                    note("O motor de voz do Android não respondeu; vou responder só por escrito. Toque no anel para falar.", true);
                } else {
                    note("Toque no anel para falar.", false);
                }
                setState(STANDBY);
                if (ui != null && JarvisTriggers.hasPending(c)) answerNotice();
            }

            @Override
            public void onSpeakingStart() {
                if (state == THINKING) setState(SPEAKING);
            }

            @Override
            public void onAllDone() {
                if (state == SPEAKING || state == THINKING) afterAnswer();
            }
        });
    }

    // ---- who is showing it ----

    public void attach(Ui u) {
        ui = u;
        u.onState(state);
        u.onUser(userText, true);
        if (answer.length() > 0) u.onAnswer(clean(answer.toString()));
        else u.onNote(noteText, noteWarn);
    }

    public void detach(Ui u) {
        if (ui == u) ui = null;
    }

    public boolean shown() {
        return ui != null;
    }

    public int state() {
        return state;
    }

    public boolean micOn() {
        return listening;
    }

    public String network() {
        return network;
    }

    private void setState(int s) {
        state = s;
        if (ui != null) ui.onState(s);
    }

    private void note(String text, boolean warn) {
        noteText = text;
        noteWarn = warn;
        answer.setLength(0);
        if (ui != null) ui.onNote(text, warn);
    }

    public boolean hasMic() {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- listening ----

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(c);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {
                if (ui != null) ui.onLevel((rmsdB + 2f) / 12f);
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                if (ui != null) ui.onLevel(0);
            }

            @Override
            public void onError(int error) {
                heardNothing(error);
            }

            @Override
            public void onResults(Bundle results) {
                heard(first(results), true);
            }

            @Override
            public void onPartialResults(Bundle partial) {
                heard(first(partial), false);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
    }

    public void listen() {
        if (closed) return;
        if (exitRequested) {
            exit();
            return;
        }
        if (ui == null) {
            // nobody is showing the conversation: the microphone stays off
            setState(STANDBY);
            return;
        }
        if (!AiConfig.hasKey(c)) {
            note("Sem chave da IA. Saia e use no terminal: ai -key", true);
            setState(STANDBY);
            return;
        }
        if (!hasMic()) {
            setState(STANDBY);
            ui.onNeedMic();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            note("Este celular está sem reconhecimento de fala (o app Google ou o serviço de voz está desativado).", true);
            setState(STANDBY);
            return;
        }

        try {
            ensureRecognizer();
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
            recognizer.startListening(i);
            listening = true;
            setState(LISTENING);
        } catch (Exception e) {
            listening = false;
            note("Não foi possível ligar o reconhecimento de fala.", true);
            setState(STANDBY);
        }
    }

    private void stopListening() {
        listening = false;
        if (ui != null) ui.onLevel(0);
        if (recognizer == null) return;
        try {
            recognizer.cancel();
        } catch (Exception ignored) {}
    }

    /** Microphone off, now. */
    public void mute() {
        if (state != LISTENING) return;
        turnId++;
        stopListening();
        setState(STANDBY);
    }

    private void heard(String text, boolean complete) {
        if (state != LISTENING) return;
        if (text.length() == 0) {
            if (complete) heardNothing(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }

        userText = text;
        if (ui != null) ui.onUser(text, complete);
        if (!complete) return;

        listening = false;
        silentRounds = 0;

        String plain = text.toLowerCase(new Locale("pt", "BR")).replaceAll("[^a-zà-ú ]", "").trim();
        if (plain.equals("sair") || plain.equals("sair do modo jarvis") || plain.equals("sair jarvis")
                || plain.equals("fechar jarvis") || plain.equals("encerrar jarvis")) {
            exit();
            return;
        }

        ask(text, false);
    }

    // Written notes that clear up what he hears ("Ezer, não Wesley"). They are sent with the next turn after a change,
    // and stay in the conversation history from then on. Memory only: they go away with the session.
    private String addenda = "";
    private boolean addendaDirty;

    public String addenda() {
        return addenda;
    }

    public void setAddenda(String text) {
        text = text == null ? "" : text.trim();
        if (text.equals(addenda)) return;
        addenda = text;
        addendaDirty = true;
    }

    /** Text to put before the next phrase, once per change. */
    private String takeAddenda() {
        if (!addendaDirty) return "";
        addendaDirty = false;
        if (addenda.length() == 0) return "[ADENDOS: ele apagou os adendos anteriores; vale só o que você ouvir agora.]\n";
        return "[ADENDOS escritos por ele, à mão. Valem MAIS do que o que você ouviu por voz (o reconhecimento de fala erra, "
                + "principalmente nomes). Use-os para entender este e os próximos pedidos, e não os comente:\n" + addenda + "]\n";
    }

    /** The user wrote the question instead of speaking it (a tap on his own line of the full screen). */
    public void typed(String text) {
        if (closed || text == null) return;
        text = text.trim();
        if (text.length() == 0) return;

        if (state == THINKING || state == SPEAKING) {
            turnId++;
            JarvisClient.cancel();
            if (voice != null) voice.stop();
            network = "ok";
        } else if (state == LISTENING) {
            turnId++;
            stopListening();
        }

        listening = false;
        silentRounds = 0;
        userText = text;
        if (ui != null) ui.onUser(text, true);

        String plain = text.toLowerCase(new Locale("pt", "BR")).replaceAll("[^a-zà-ú ]", "").trim();
        if (plain.equals("sair") || plain.equals("sair do modo jarvis") || plain.equals("sair jarvis")
                || plain.equals("fechar jarvis") || plain.equals("encerrar jarvis")) {
            exit();
            return;
        }

        ask(text, false);
    }

    private void heardNothing(int error) {
        if (state != LISTENING) return;
        listening = false;
        if (ui != null) ui.onLevel(0);

        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            silentRounds++;
            if (silentRounds >= MAX_SILENT_ROUNDS) {
                silentRounds = 0;
                setState(STANDBY);
            } else {
                final int id = turnId;
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId && state == LISTENING && !listening) listen();
                    }
                }, 500);
            }
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            note("O Android negou o microfone ao reconhecimento de fala.", true);
            setState(STANDBY);
        } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER) {
            note("O reconhecimento de fala ficou sem rede. Toque no anel para tentar de novo.", true);
            setState(STANDBY);
        } else {
            note("O reconhecimento de fala falhou (código " + error + "). Toque no anel para tentar de novo.", true);
            setState(STANDBY);
        }
    }

    /** A tap on the rings or on the bubble: turns the microphone on, interrupts the answer, or turns it off. */
    public void tap() {
        if (closed) return;
        if (state == THINKING || state == SPEAKING) {
            turnId++;
            JarvisClient.cancel();
            if (voice != null) voice.stop();
            network = "ok";
            listen();
        } else if (state == STANDBY) {
            if (JarvisTriggers.hasPending(c)) {
                answerNotice();
                return;
            }
            silentRounds = 0;
            listen();
        } else if (state == LISTENING) {
            mute();
        }
    }

    // ---- he wants to talk ----

    /** A trigger fired while this conversation is on screen. True when the user was told here. */
    public boolean announce() {
        if (closed || ui == null) return false;
        if (state == STANDBY) note("Jarvis quer falar com você. Toque no anel para ouvir.", true);
        return true;
    }

    /** The user answered "Jarvis quer falar com você": he speaks first, about what made him call. */
    public void answerNotice() {
        if (closed || state == THINKING || state == SPEAKING) return;
        String reasons = JarvisTriggers.pendingText(c);
        if (reasons.length() == 0) return;
        JarvisTriggers.clearPending(c);
        if (state == LISTENING) stopListening();

        userText = "";
        if (ui != null) ui.onUser("", true);
        ask("[AVISO DO SISTEMA, não é fala dele. Foi você quem chamou: apareceu na tela \"Jarvis quer falar com você\" "
                + "e ele acabou de atender. O motivo de você ter chamado:\n" + reasons
                + "\nFale primeiro, do seu jeito e curto, sobre isso. Não mencione este aviso.]", true);
    }

    // ---- one turn ----

    private void ask(String phrase, boolean fromNotice) {
        final int id = ++turnId;

        answer.setLength(0);
        pending.setLength(0);
        noteText = "";
        if (ui != null) ui.onNote("", false);
        network = "enviando";
        setState(THINKING);
        if (voice != null) voice.begin();
        if (!fromNotice) JarvisTriggers.talked(c);

        JarvisClient.ask(c, fromNotice ? phrase : takeAddenda() + phrase, fromNotice, new JarvisClient.Listener() {
            @Override
            public void onText(final String delta) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId && !closed) more(delta);
                    }
                });
            }

            @Override
            public void onTool(final String name) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id != turnId || closed || ui == null) return;
                        if (answer.length() == 0 || !(ui instanceof JarvisActivity.ScreenUi)) ui.onNote("> " + name, false);
                        ui.onData();
                    }
                });
            }

            @Override
            public void onDone(final String full) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId && !closed) done();
                    }
                });
            }

            @Override
            public void onError(final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId && !closed) failed(message);
                    }
                });
            }
        });
    }

    private void more(String delta) {
        if (answer.length() == 0) network = "recebendo";
        answer.append(delta);
        if (ui != null) ui.onAnswer(clean(answer.toString()));
        pending.append(delta);
        cut(false);
    }

    static String clean(String s) {
        return JarvisClient.withoutMoodMark(s).replaceAll("[*_#`]+", "").replaceAll("[ \\t]+", " ").trim();
    }

    /**
     * Hands complete sentences of the answer to the voice. A piece ends only at a full stop, question or exclamation
     * mark: cutting at commas made the voice stop in the middle of a sentence while the next piece was being prepared.
     */
    private void cut(boolean everything) {
        while (true) {
            int end = -1;
            for (int i = 0; i < pending.length(); i++) {
                char ch = pending.charAt(i);
                if (ch != '.' && ch != '!' && ch != '?' && ch != '\n' && ch != '…') continue;

                // the mark must be followed by a space: "3.5" or "..." in the middle are not an end
                if (i + 1 >= pending.length()) {
                    if (ch == '\n') end = i;
                    continue;
                }
                char after = pending.charAt(i + 1);
                if (ch == '\n' || after == ' ' || after == '\n') {
                    end = i;
                    break;
                }
            }
            if (end < 0) break;

            String piece = clean(pending.substring(0, end + 1));
            pending.delete(0, end + 1);
            say(piece);
        }

        if (everything) {
            String rest = clean(pending.toString());
            pending.setLength(0);
            say(rest);
        }
    }

    private void say(String piece) {
        if (voice == null || !voiceOk) return;
        if (piece.replaceAll("[^\\p{L}\\p{N}]", "").length() == 0) return;
        voice.speak(piece);
    }

    private void done() {
        network = "ok";
        cut(true);
        if (ui != null) ui.onData();
        if (clean(answer.toString()).length() == 0) note("(sem resposta)", false);

        if (voice != null && voiceOk && voice.isBusy()) voice.end();   // onAllDone goes on from there
        else afterAnswer();
    }

    private void afterAnswer() {
        if (exitRequested) {
            exit();
            return;
        }
        silentRounds = 0;
        listen();
    }

    private void failed(String message) {
        network = "ok";
        if (voice != null) voice.stop();
        if (answer.length() == 0) note("jarvis: " + message + ". Toque no anel para falar de novo.", true);
        else if (ui != null) ui.onAnswer(clean(answer.toString()) + "\n\n[" + message + "]");
        setState(STANDBY);
    }

    // ---- the end ----

    /** The user asked to leave (button, the word "sair", or Jarvis closing his screen at his request). */
    public void exit() {
        Ui u = ui;
        close();
        if (u != null) u.onExit();
    }

    /** Frees everything, at once. */
    public void close() {
        if (closed) return;
        closed = true;
        exitRequested = false;
        JarvisActivity.active = false;
        turnId++;
        main.removeCallbacksAndMessages(null);
        JarvisClient.cancel();

        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }
        if (voice != null) voice.shutdown();
        voice = null;

        JarvisMicService.stop(c);
        JarvisAccess access = JarvisAccess.instance;
        if (access != null) access.hideBubble();

        synchronized (JarvisSession.class) {
            if (instance == this) instance = null;
        }
        ui = null;
    }
}
