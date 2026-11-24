package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The voice of Jarvis. It speaks through Android's standard text-to-speech, so it uses whatever engine the user
 * chose in the system settings (Google, Samsung, or a neural one such as Marmalade), with no key and no network.
 *
 * Neural engines are slow to start on a modest phone. To hide the pauses, the answer is spoken in pieces:
 * each piece is synthesized to a file and played, and while one piece is playing the next one is already being
 * prepared. Everything here runs on the main thread.
 */
public class JarvisVoice {

    public interface Listener {
        void onReady(boolean ok);

        void onSpeakingStart();

        /** Everything given to speak() was spoken and end() had been called. */
        void onAllDone();
    }

    private static final String VOICE = "voz";
    private static final String RATE = "voz_velocidade";
    private static final String PITCH = "voz_tom";
    private static final String LISTED = "voz_lista";

    private static class Item {
        String id;
        File file;
        boolean done, failed;
        int chars;
        long synthStart;   // 0 while it waits for the previous piece to be synthesized
    }

    // how the engine behaves on this phone, learned from the pieces already spoken (milliseconds per character)
    private static double synthPerChar = 0, audioPerChar = 0;

    private static final long MAX_HOLD_MS = 9000;

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private Listener listener;

    private TextToSpeech tts;
    private boolean ready, released, closeWhenDone;

    private final List<Item> items = new ArrayList<>();
    private int playIndex;
    private MediaPlayer player;
    private boolean inputClosed, started, finished = true;
    private int turn, seq;
    private long holdSince;
    private final Runnable pumpLater = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };

    public JarvisVoice(Context context, Listener l) {
        c = context.getApplicationContext();
        listener = l;
        dir = new File(c.getCacheDir(), "jarvis_voz");
        dir.mkdirs();
        wipe();

        tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        init(status);
                    }
                });
            }
        });
    }

    private void wipe() {
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private void init(int status) {
        if (released || tts == null) return;

        if (status != TextToSpeech.SUCCESS) {
            if (listener != null) listener.onReady(false);
            return;
        }

        apply(c, tts);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {}

            @Override
            public void onDone(final String id) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        mark(id, false);
                    }
                });
            }

            @Override
            public void onError(final String id) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        mark(id, true);
                    }
                });
            }
        });

        ready = true;

        // wakes the engine up: the first synthesis loads the model, so it happens before the first answer
        try {
            tts.synthesizeToFile("ok", new Bundle(), new File(dir, "aquecer.wav"), "aquecer");
        } catch (Exception ignored) {}

        if (listener != null) listener.onReady(true);
    }

    public boolean isReady() {
        return ready && !released;
    }

    /** Starts a new answer: whatever was being spoken is dropped. */
    public void begin() {
        halt();
        turn++;
        seq = 0;
        inputClosed = false;
        started = false;
        finished = false;
    }

    /** Queues one piece of the answer. */
    public void speak(String text) {
        if (!isReady() || finished || text == null || text.trim().length() == 0) return;

        Item it = new Item();
        it.id = "t" + turn + "-" + (seq++);
        it.file = new File(dir, it.id + ".wav");
        it.chars = Math.max(1, text.trim().length());
        it.synthStart = synthesizing() ? 0 : System.currentTimeMillis();
        items.add(it);

        int result;
        try {
            result = tts.synthesizeToFile(text.trim(), new Bundle(), it.file, it.id);
        } catch (Exception e) {
            result = TextToSpeech.ERROR;
        }

        if (result != TextToSpeech.SUCCESS) {
            it.done = true;
            it.failed = true;
            pump();
        }
    }

    private boolean synthesizing() {
        for (Item it : items) if (!it.done) return true;
        return false;
    }

    /** No more pieces will come for this answer. */
    public void end() {
        if (finished) return;
        inputClosed = true;
        pump();
    }

    public boolean isBusy() {
        return !finished;
    }

    /** Silence, now. The listener is not told. */
    public void stop() {
        halt();
        finished = true;
        inputClosed = false;
    }

    private void halt() {
        try {
            if (tts != null && ready) tts.stop();
        } catch (Exception ignored) {}
        releasePlayer();
        main.removeCallbacks(pumpLater);
        holdSince = 0;
        for (Item it : items) it.file.delete();
        items.clear();
        playIndex = 0;
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.release();
        } catch (Exception ignored) {}
        player = null;
    }

    private void mark(String id, boolean failed) {
        if (released) return;

        if ("aquecer".equals(id)) {
            new File(dir, "aquecer.wav").delete();
            return;
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.id.equals(id)) continue;

            it.done = true;
            it.failed = failed;

            if (!failed && it.synthStart > 0) {
                double perChar = (now - it.synthStart) / (double) it.chars;
                synthPerChar = synthPerChar == 0 ? perChar : synthPerChar * 0.5 + perChar * 0.5;
            }

            // the engine works on one piece at a time: the next one starts now
            for (int j = i + 1; j < items.size(); j++) {
                if (!items.get(j).done) {
                    if (items.get(j).synthStart == 0) items.get(j).synthStart = now;
                    break;
                }
            }
            break;
        }
        pump();
    }

    private void pump() {
        if (released || finished || player != null) return;

        while (playIndex < items.size()) {
            Item it = items.get(playIndex);
            if (!it.done) return;

            if (it.failed || !it.file.isFile() || it.file.length() < 64) {
                it.file.delete();
                playIndex++;
                continue;
            }

            play(it);
            return;
        }

        if (inputClosed) {
            finished = true;
            items.clear();
            playIndex = 0;
            if (listener != null) listener.onAllDone();
            if (closeWhenDone) shutdown();
        }
    }

    private void play(final Item it) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(it.file.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    next(it);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    next(it);
                    return true;
                }
            });
            player.prepare();

            int duration = player.getDuration();
            if (duration > 0) {
                double perChar = duration / (double) it.chars;
                audioPerChar = audioPerChar == 0 ? perChar : audioPerChar * 0.5 + perChar * 0.5;
            }

            // Before the first word, wait as long as needed for the rest to be ready in time: one longer silence
            // at the start instead of stops in the middle of the speech.
            if (!started) {
                long wait = waitBeforeStart(Math.max(0, duration));
                long now = System.currentTimeMillis();
                if (holdSince == 0) holdSince = now;
                if (wait > 120 && now - holdSince < MAX_HOLD_MS) {
                    releasePlayer();
                    main.removeCallbacks(pumpLater);
                    main.postDelayed(pumpLater, Math.min(wait, 350));
                    return;
                }
            }

            player.start();

            if (!started) {
                started = true;
                if (listener != null) listener.onSpeakingStart();
            }
        } catch (Exception e) {
            next(it);
        }
    }

    /**
     * How long the first piece should still wait so that every piece after it is ready when its turn comes.
     * Uses what was learned about the engine: time to synthesize and time to speak, per character.
     */
    private long waitBeforeStart(long firstDuration) {
        if (synthPerChar <= 0 || audioPerChar <= 0) return 0;

        long now = System.currentTimeMillis();
        double readyAt = 0;        // when the piece being looked at will be synthesized, from now
        double playsAt = firstDuration;   // when it would start playing if the first piece started now
        double wait = 0;

        for (int i = playIndex + 1; i < items.size(); i++) {
            Item it = items.get(i);
            if (!it.done) {
                double whole = synthPerChar * it.chars;
                double left = it.synthStart > 0 ? Math.max(0, whole - (now - it.synthStart)) : whole;
                readyAt += left;
                wait = Math.max(wait, readyAt - playsAt);
            }
            playsAt += audioPerChar * it.chars;
        }
        return Math.round(wait);
    }

    private void next(Item it) {
        if (released || playIndex >= items.size() || items.get(playIndex) != it) return;
        releasePlayer();
        it.file.delete();
        playIndex++;
        pump();
    }

    /** Lets the current answer finish (the screen is going away) and then frees the engine. */
    public void shutdownWhenDone() {
        listener = null;
        if (finished || !ready) {
            shutdown();
            return;
        }
        closeWhenDone = true;
        inputClosed = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, 40000);
        pump();
    }

    public void shutdown() {
        if (released) return;
        released = true;
        listener = null;
        releasePlayer();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        tts = null;
        wipe();
    }

    // ---- choice of voice ("ai -jarvis -voz") ----

    private static SharedPreferences prefs(Context c) {
        return JarvisMemory.state(c);
    }

    private static float number(SharedPreferences p, String key) {
        float v = p.getFloat(key, 1f);
        return v < 0.3f || v > 3f ? 1f : v;
    }

    /** Voice, speed and pitch saved by the user; the system default when nothing was chosen. */
    private static void apply(Context c, TextToSpeech tts) {
        SharedPreferences p = prefs(c);
        String wanted = p.getString(VOICE, "");

        boolean set = false;
        if (wanted.length() > 0) {
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (wanted.equals(v.getName())) {
                            set = tts.setVoice(v) == TextToSpeech.SUCCESS;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!set) {
            try {
                tts.setLanguage(new Locale("pt", "BR"));
            } catch (Exception ignored) {}
        }

        try {
            tts.setSpeechRate(number(p, RATE));
            tts.setPitch(number(p, PITCH));
        } catch (Exception ignored) {}
    }

    private interface WithEngine {
        void run(TextToSpeech tts);
    }

    /** Opens the engine just for one job in the terminal and closes it. */
    private static void withEngine(final Context context, final WithEngine job) {
        final Context c = context.getApplicationContext();
        final TextToSpeech[] holder = new TextToSpeech[1];
        final Handler main = new Handler(Looper.getMainLooper());

        holder[0] = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        TextToSpeech tts = holder[0];
                        if (tts == null) return;
                        if (status != TextToSpeech.SUCCESS) {
                            AiClient.warn(c, "jarvis: o motor de voz do Android não respondeu. Veja Configurações > texto em voz.");
                            try {
                                tts.shutdown();
                            } catch (Exception ignored) {}
                            return;
                        }
                        try {
                            job.run(tts);
                        } catch (Exception e) {
                            AiClient.warn(c, "jarvis: não foi possível falar com o motor de voz.");
                            try {
                                tts.shutdown();
                            } catch (Exception ignored) {}
                        }
                    }
                });
            }
        });
    }

    private static void closeLater(final TextToSpeech tts, long ms) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    tts.stop();
                    tts.shutdown();
                } catch (Exception ignored) {}
            }
        }, ms);
    }

    /** "ai -jarvis -voz": the voices of the engine in use, Portuguese ones first, numbered to be chosen. */
    public static void list(Context context) {
        final Context c = context.getApplicationContext();
        withEngine(c, new WithEngine() {
            @Override
            public void run(TextToSpeech tts) {
                List<Voice> voices = new ArrayList<>();
                Set<Voice> all = tts.getVoices();
                if (all != null) voices.addAll(all);

                Collections.sort(voices, new Comparator<Voice>() {
                    @Override
                    public int compare(Voice a, Voice b) {
                        boolean pa = isPortuguese(a), pb = isPortuguese(b);
                        if (pa != pb) return pa ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });

                SharedPreferences p = prefs(c);
                String current = p.getString(VOICE, "");

                StringBuilder b = new StringBuilder("== jarvis: voz ==");
                b.append("\nmotor: ").append(tts.getDefaultEngine());
                b.append(String.format(Locale.US, "\nvelocidade: %.2f   tom: %.2f", number(p, RATE), number(p, PITCH)));
                b.append("\natual: ").append(current.length() == 0 ? "a padrão do motor" : current);

                JSONArray names = new JSONArray();
                int shown = 0;
                for (Voice v : voices) {
                    if (shown >= 40) break;
                    if (!isPortuguese(v) && shown >= 12) continue;
                    names.put(v.getName());
                    shown++;
                    b.append('\n').append(v.getName().equals(current) ? "* " : "  ").append(shown).append(". ")
                            .append(v.getName()).append("  [").append(v.getLocale() == null ? "?" : v.getLocale().toString()).append("]");
                }
                if (shown == 0) b.append("\n(o motor não informou vozes)");
                p.edit().putString(LISTED, names.toString()).apply();

                b.append("\nEscolher: ai -jarvis -voz [número]   Ouvir: ai -jarvis -voz -teste");
                b.append("\nAjustar: ai -jarvis -voz -velocidade 0.9   ai -jarvis -voz -tom 0.8   Voltar à padrão: ai -jarvis -voz -padrao");
                b.append("\nO motor (Google, Samsung, Marmalade...) é escolhido nas Configurações do Android, em texto em voz.");
                AiClient.info(c, b.toString());

                closeLater(tts, 200);
            }
        });
    }

    private static boolean isPortuguese(Voice v) {
        Locale l = v.getLocale();
        return l != null && "pt".equalsIgnoreCase(l.getLanguage());
    }

    /** Picks the voice with this number in the last list. Returns the line to print. */
    public static String choose(Context c, int number) {
        try {
            JSONArray names = new JSONArray(prefs(c).getString(LISTED, "[]"));
            if (number < 1 || number > names.length()) return "jarvis: número fora da lista. Veja a lista com ai -jarvis -voz";
            String name = names.getString(number - 1);
            prefs(c).edit().putString(VOICE, name).apply();
            return "jarvis: voz = " + name + ". Ouça com ai -jarvis -voz -teste";
        } catch (Exception e) {
            return "jarvis: rode ai -jarvis -voz primeiro, para ver a lista.";
        }
    }

    public static String useDefault(Context c) {
        prefs(c).edit().remove(VOICE).remove(RATE).remove(PITCH).apply();
        return "jarvis: voz, velocidade e tom de volta ao padrão do motor.";
    }

    public static String setRate(Context c, String value, boolean pitch) {
        try {
            float v = Float.parseFloat(value.replace(',', '.'));
            if (v < 0.3f || v > 3f) throw new NumberFormatException();
            prefs(c).edit().putFloat(pitch ? PITCH : RATE, v).apply();
            return "jarvis: " + (pitch ? "tom" : "velocidade") + " = " + v + " (1 é o normal). Ouça com ai -jarvis -voz -teste";
        } catch (Exception e) {
            return "jarvis: use um número entre 0.3 e 3. Exemplo: ai -jarvis -voz " + (pitch ? "-tom 0.8" : "-velocidade 0.9");
        }
    }

    public static void test(Context context) {
        final Context c = context.getApplicationContext();
        withEngine(c, new WithEngine() {
            @Override
            public void run(TextToSpeech tts) {
                apply(c, tts);
                tts.speak("Teste de voz. Infelizmente, estou funcionando.", TextToSpeech.QUEUE_FLUSH, new Bundle(), "teste");
                closeLater(tts, 20000);
            }
        });
    }
}
