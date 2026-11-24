package ohi.andre.consolelauncher.managers.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ohi.andre.consolelauncher.managers.reminder.ReminderStore;
import ohi.andre.consolelauncher.managers.tlau.TlauUser;

/**
 * "ai -jarvis": a full screen where the user only talks. The microphone is asked for when the screen opens and
 * is released when it closes. One turn is: listen, send to the model, write and speak the answer, listen again.
 */
public class JarvisActivity extends Activity {

    private static final int ASK_MIC = 71;

    private static final int BOOT = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, STANDBY = 4;
    // The microphone is never on by itself: the screen opens muted and a tap on the rings turns it on.
    // After an answer he listens once more, so a conversation can go on; one silence and it is muted again.
    private static final int MAX_SILENT_ROUNDS = 1;

    private static final int AMBER = 0xFFFFB454, WHITE = 0xFFF2FFF6, INK = 0xFFE9FFF1;

    private final Handler main = new Handler(Looper.getMainLooper());

    private int accent, dim, track, faint, ground;
    private boolean erick;
    private Typeface font;

    private JarvisHudView hud;
    private Backdrop backdrop;
    private TextView clock, youLabel, youText, jarvisLabel, jarvisText, micText, micDot;
    private TextView[] cellLabel = new TextView[4], cellValue = new TextView[4], cellNote = new TextView[4];
    private ScrollView scroll;

    private SpeechRecognizer recognizer;
    private JarvisVoice voice;

    private int state = BOOT;
    private boolean micGranted, voiceAnswered, voiceOk, resumed, listening;
    private boolean quit, listenOnResume;

    /**
     * True while the Jarvis screen is "on": from the moment it opens until the user leaves with the button or by
     * asking. While it is true the launcher sends the user back here, so this screen works as the home screen.
     * It lives only in memory: if the app process dies, the terminal is the home screen again.
     */
    public static volatile boolean active;

    /** Set by the tool Jarvis uses to close his own screen; obeyed when he finishes talking. */
    public static volatile boolean exitRequested;
    private int silentRounds, turnId;
    private String network = "ok";

    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean firstPiece;

    public static void open(Context c) {
        Intent i = new Intent(c, JarvisActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    // ---- screen ----

    private int px(float dpValue) {
        return Math.round(dpValue * getResources().getDisplayMetrics().density);
    }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (font != null) t.setTypeface(font);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View rule() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View a = new View(this);
        a.setBackgroundColor(accent);
        row.addView(a, new LinearLayout.LayoutParams(px(34), px(3)));

        View b = new View(this);
        b.setBackgroundColor(faint);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, px(1), 1f);
        lb.leftMargin = px(8);
        lb.rightMargin = px(8);
        row.addView(b, lb);

        View d = new View(this);
        d.setBackgroundColor(accent);
        row.addView(d, new LinearLayout.LayoutParams(px(12), px(3)));
        return row;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        active = true;
        exitRequested = false;

        erick = TlauUser.ERICK.equals(TlauUser.current(this));
        accent = erick ? 0xFF4FD8FF : 0xFF3DFF8F;
        dim = erick ? 0xFF8FB4C2 : 0xFF8DB89D;
        track = erick ? 0xFF12323D : 0xFF123321;
        faint = erick ? 0xFF1A4656 : 0xFF1A4A2F;
        ground = erick ? 0xFF03060A : 0xFF030604;
        int gridColor = erick ? 0xFF0A1A20 : 0xFF0A1C11;

        try {
            font = Typeface.createFromAsset(getAssets(), "lucida_console.ttf");
        } catch (Exception e) {
            font = Typeface.MONOSPACE;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ground);

        backdrop = new Backdrop(this, gridColor);
        backdrop.color = accent;
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(22), px(30), px(22), px(24));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // header
        LinearLayout head = new LinearLayout(this);
        TextView title = label(erick ? ">>e-lau :: jarvis" : ">>t-lau :: jarvis", 12, accent);
        title.setTypeface(font, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clock = label("", 12, dim);
        head.addView(clock);
        col.addView(head);

        LinearLayout.LayoutParams gapTop = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapTop.topMargin = px(12);
        col.addView(rule(), gapTop);

        // rings
        hud = new JarvisHudView(this, font);
        hud.setPalette(accent, dim, track, faint, ground);
        hud.setMark(erick ? "E>_" : "T>_");
        hud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tapped();
            }
        });
        LinearLayout.LayoutParams lh = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f);
        lh.topMargin = px(12);
        col.addView(hud, lh);

        // what was said
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout talk = new LinearLayout(this);
        talk.setOrientation(LinearLayout.VERTICAL);
        talk.setPadding(px(12), 0, 0, 0);

        GradientDrawable bar = new GradientDrawable();
        bar.setColor(0);
        scroll.setBackground(bar);

        youLabel = label(erick ? "// ERICK" : "// TEETSUUO", 10, dim);
        talk.addView(youLabel);
        youText = label("", 14, dim);
        youText.setLineSpacing(px(3), 1f);
        youText.setPadding(0, px(4), 0, px(10));
        talk.addView(youText);
        jarvisLabel = label("// JARVIS", 10, dim);
        talk.addView(jarvisLabel);
        jarvisText = label("", 17, INK);
        jarvisText.setLineSpacing(px(4), 1f);
        jarvisText.setPadding(0, px(4), 0, px(8));
        talk.addView(jarvisText);
        scroll.addView(talk);

        LinearLayout talkRow = new LinearLayout(this);
        View side = new View(this);
        side.setBackgroundColor(accent);
        talkRow.addView(side, new LinearLayout.LayoutParams(px(2), ViewGroup.LayoutParams.MATCH_PARENT));
        talkRow.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lt.topMargin = px(14);
        col.addView(talkRow, lt);

        // strip of data
        LinearLayout strip = new LinearLayout(this);
        String[] names = {"BAT", "IA CICLO", "LEMBRETE", "HUMOR"};
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cellLabel[i] = label(names[i], 9.5f, dim);
            cellValue[i] = label("", 13, INK);
            cellValue[i].setPadding(0, px(3), 0, px(2));
            cellNote[i] = label("", 9.5f, dim);
            cell.addView(cellLabel[i]);
            cell.addView(cellValue[i]);
            cell.addView(cellNote[i]);
            strip.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ls.topMargin = px(12);
        col.addView(strip, ls);

        LinearLayout.LayoutParams gapBottom = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gapBottom.topMargin = px(12);
        col.addView(rule(), gapBottom);

        // footer: microphone and exit
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        micDot = label("■ ", 12, dim);
        foot.addView(micDot);
        micText = label("microfone desligado", 12, dim);
        foot.addView(micText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button exit = new Button(this);
        exit.setText("[ SAIR ]");
        exit.setAllCaps(false);
        exit.setTextColor(accent);
        exit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        if (font != null) exit.setTypeface(font);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0);
        border.setStroke(px(1), accent);
        exit.setBackground(border);
        exit.setMinHeight(px(46));
        exit.setMinimumHeight(px(46));
        exit.setPadding(px(16), 0, px(16), 0);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leave();
            }
        });
        foot.addView(exit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(46)));

        LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lf.topMargin = px(12);
        col.addView(foot, lf);

        setContentView(root);

        setState(BOOT);
        refreshData();

        // being called between midnight and five is not something he takes well
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5 && !JarvisClient.isOpen()) JarvisMood.nudge(this, 15, "foi acordado de madrugada");

        if (!AiConfig.hasKey(this)) {
            note("Sem chave da IA. Saia e use no terminal: ai -key", AMBER);
            setState(STANDBY);
            return;
        }

        voice = new JarvisVoice(this, new JarvisVoice.Listener() {
            @Override
            public void onReady(boolean ok) {
                voiceAnswered = true;
                voiceOk = ok;
                if (!ok) note("O motor de voz do Android não respondeu; vou responder só por escrito.", AMBER);
                maybeStart();
            }

            @Override
            public void onSpeakingStart() {
                if (state == THINKING) setState(SPEAKING);
            }

            @Override
            public void onAllDone() {
                if (state == SPEAKING || state == THINKING) listen();
            }
        });

        micGranted = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micGranted) {
            note("O Android vai perguntar se o T-Lau pode usar o microfone. Ele só fica ligado dentro deste modo.", dim);
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_MIC) return;
        micGranted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (micGranted) {
            maybeStart();
        } else {
            note("Sem o microfone o modo Jarvis não funciona. Para liberar: Configurações > Apps > T-Lau > Permissões.", AMBER);
            setState(STANDBY);
        }
    }

    private void maybeStart() {
        if (!resumed || !micGranted || !voiceAnswered || state != BOOT) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            note("Este celular está sem reconhecimento de fala (o app Google ou o serviço de voz está desativado).", AMBER);
            setState(STANDBY);
            return;
        }
        note("Toque no anel para falar.", dim);
        setState(STANDBY);
    }

    /** Small grey or amber line in the place of the answer (status, errors). */
    private void note(String s, int color) {
        jarvisText.setTextColor(color);
        jarvisText.setTextSize(TypedValue.COMPLEX_UNIT_SP, color == INK ? 17 : 13);
        jarvisText.setText(s);
    }

    private void setState(int s) {
        state = s;
        int c;
        switch (s) {
            case LISTENING:
                c = accent;
                hud.setState("OUVINDO", c, 1.2f, false, 1f, JarvisHudView.BARS_LEVEL);
                break;
            case THINKING:
                c = AMBER;
                hud.setState("PENSANDO", c, 4f, true, 1f, JarvisHudView.BARS_FLAT);
                break;
            case SPEAKING:
                c = WHITE;
                hud.setState("RESPONDENDO", c, 2f, false, 1f, JarvisHudView.BARS_TALK);
                break;
            case STANDBY:
                c = dim;
                hud.setState("EM ESPERA", c, 0.4f, false, 0.15f, JarvisHudView.BARS_FLAT);
                break;
            default:
                c = accent;
                hud.setState("INICIANDO", c, 0.6f, true, 1f, JarvisHudView.BARS_FLAT);
        }
        backdrop.color = c;
        backdrop.invalidate();

        // the screen stays awake only while a conversation is going on; at rest it may turn off like any home screen
        if (s == LISTENING || s == THINKING || s == SPEAKING) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        showMic();
        refreshData();
    }

    private void showMic() {
        String s;
        int c;
        if (listening) {
            s = "microfone ligado";
            c = accent;
        } else if (state == THINKING || state == SPEAKING) {
            s = "microfone em pausa · toque no anel para interromper";
            c = AMBER;
        } else if (state == STANDBY) {
            s = "microfone desligado · toque no anel para falar";
            c = dim;
        } else {
            s = "microfone desligado";
            c = dim;
        }
        micText.setText(s);
        micText.setTextColor(c);
        micDot.setTextColor(c);
    }

    private void refreshData() {
        clock.setText(new SimpleDateFormat("EEE dd/MM · HH:mm", new Locale("pt", "BR")).format(new Date()).toUpperCase(new Locale("pt", "BR")));

        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            cellValue[0].setText(level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : "?");
            cellNote[0].setText(plugged != 0 ? "carregando" : "");
        } catch (Exception e) {
            cellValue[0].setText("?");
        }

        double spent = AiUsage.cycle(this).cost, limit = AiConfig.limitUsd(this);
        cellValue[1].setText(AiUsage.money(spent).replace("US$ ", "$"));
        cellNote[1].setText(limit > 0 ? String.format(Locale.US, "%.1f%% do limite", spent * 100 / limit) : "sem limite");

        long now = System.currentTimeMillis(), next = 0;
        try {
            List<ReminderStore.Reminder> all = ReminderStore.all(this);
            for (ReminderStore.Reminder r : all) if (r.when > now && (next == 0 || r.when < next)) next = r.when;
        } catch (Exception ignored) {}
        if (next == 0) {
            cellValue[2].setText("nenhum");
            cellNote[2].setText("");
        } else {
            cellValue[2].setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(next)));
            Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
            b.setTimeInMillis(next);
            boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
            cellNote[2].setText(today ? "hoje" : new SimpleDateFormat("dd/MM", Locale.US).format(new Date(next)));
        }

        int mood = JarvisMood.get(this);
        cellValue[3].setText(mood + "%");
        cellValue[3].setTextColor(mood >= 92 ? 0xFFFF6B5E : mood >= 55 ? AMBER : INK);
        cellNote[3].setText(JarvisMood.label(mood));

        hud.setReadout(0, "MODELO", JarvisClient.model().replace("claude-", "").replaceAll("-[0-9].*$", ""), 0);
        hud.setReadout(1, "REDE", network, "ok".equals(network) ? 0 : AMBER);
        long tokens = JarvisClient.contextTokens();
        hud.setReadout(2, "CONVERSA", tokens <= 0 ? "nova" : tokens < 1000 ? tokens + " tk" : String.format(Locale.US, "%.1fk tk", tokens / 1000.0), 0);
        hud.setReadout(3, "MIC", listening ? "on" : state == THINKING || state == SPEAKING ? "pausa" : "off",
                listening ? accent : state == THINKING || state == SPEAKING ? AMBER : dim);
    }

    // ---- listening ----

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {
                hud.setLevel((rmsdB + 2f) / 12f);
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                hud.setLevel(0);
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

    private void listen() {
        if (isFinishing()) return;
        if (exitRequested) {
            leave();
            return;
        }
        if (!resumed) {
            // the screen is not in front (an app was opened, or the phone was locked): listen again when it comes back
            listenOnResume = true;
            setState(STANDBY);
            return;
        }
        if (!micGranted) return;

        try {
            ensureRecognizer();
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizer.startListening(i);
            listening = true;
            setState(LISTENING);
        } catch (Exception e) {
            listening = false;
            note("Não foi possível ligar o reconhecimento de fala.", AMBER);
            setState(STANDBY);
        }
    }

    private void stopListening() {
        listening = false;
        hud.setLevel(0);
        if (recognizer == null) return;
        try {
            recognizer.cancel();
        } catch (Exception ignored) {}
    }

    private void heard(String text, boolean complete) {
        if (state != LISTENING || text.length() == 0) {
            if (complete && state == LISTENING) heardNothing(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }

        youText.setText(text);
        youText.setTextColor(complete ? dim : INK);
        if (!complete) return;

        listening = false;
        silentRounds = 0;

        String plain = text.toLowerCase(new Locale("pt", "BR")).replaceAll("[^a-zà-ú ]", "").trim();
        if (plain.equals("sair") || plain.equals("sair do modo jarvis") || plain.equals("sair jarvis")
                || plain.equals("fechar jarvis") || plain.equals("encerrar jarvis")) {
            leave();
            return;
        }

        ask(text);
    }

    private void heardNothing(int error) {
        if (state != LISTENING) return;
        listening = false;
        hud.setLevel(0);

        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            silentRounds++;
            if (silentRounds >= MAX_SILENT_ROUNDS) {
                silentRounds = 0;
                setState(STANDBY);
            } else {
                relisten(350);
            }
        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            silentRounds++;
            if (silentRounds >= MAX_SILENT_ROUNDS) {
                silentRounds = 0;
                setState(STANDBY);
            } else {
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {}
                relisten(700);
            }
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            note("O Android negou o microfone ao reconhecimento de fala.", AMBER);
            setState(STANDBY);
        } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER) {
            note("O reconhecimento de fala ficou sem rede. Toque no anel para tentar de novo.", AMBER);
            setState(STANDBY);
        } else {
            note("O reconhecimento de fala falhou (código " + error + "). Toque no anel para tentar de novo.", AMBER);
            setState(STANDBY);
        }
    }

    private void relisten(long delay) {
        showMic();
        final int id = turnId;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id == turnId && state == LISTENING && !listening) listen();
            }
        }, delay);
    }

    /** A tap on the rings: interrupts the answer, wakes the microphone up, or puts it to rest. */
    private void tapped() {
        if (state == THINKING || state == SPEAKING) {
            turnId++;
            JarvisClient.cancel();
            if (voice != null) voice.stop();
            listen();
        } else if (state == STANDBY) {
            if (!AiConfig.hasKey(this)) return;
            if (!micGranted) {
                if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
                return;
            }
            note("", INK);
            silentRounds = 0;
            listen();
        } else if (state == LISTENING) {
            turnId++;
            stopListening();
            setState(STANDBY);
        }
    }

    // ---- one turn ----

    private void ask(String phrase) {
        final int id = ++turnId;

        answer.setLength(0);
        pending.setLength(0);
        firstPiece = true;
        note("", INK);
        network = "enviando";
        setState(THINKING);
        if (voice != null) voice.begin();

        JarvisClient.ask(this, phrase, new JarvisClient.Listener() {
            @Override
            public void onText(final String delta) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId) more(delta);
                    }
                });
            }

            @Override
            public void onTool(final String name) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id != turnId) return;
                        if (answer.length() == 0) note("> " + name, AMBER);
                        refreshData();
                    }
                });
            }

            @Override
            public void onDone(final String full) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId) done();
                    }
                });
            }

            @Override
            public void onError(final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == turnId) failed(message);
                    }
                });
            }
        });
    }

    private void more(String delta) {
        if (answer.length() == 0) {
            jarvisText.setTextColor(INK);
            jarvisText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            network = "recebendo";
        }
        answer.append(delta);
        jarvisText.setText(clean(answer.toString()));
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });

        pending.append(delta);
        cut(false);
    }

    private static String clean(String s) {
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
                boolean strong = ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '…';
                if (!strong) continue;

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
        firstPiece = false;
        voice.speak(piece);
    }

    private void done() {
        network = "ok";
        cut(true);
        refreshData();

        if (answer.toString().trim().length() == 0) note("(sem resposta)", dim);

        if (voice != null && voiceOk && voice.isBusy()) {
            voice.end();   // onAllDone turns the microphone back on
        } else {
            listen();
        }
    }

    private void failed(String message) {
        network = "erro";
        if (voice != null) voice.stop();
        if (answer.length() == 0) {
            note("jarvis: " + message + ". Toque no anel para falar de novo.", AMBER);
        } else {
            jarvisText.setText(clean(answer.toString()) + "\n\n[" + message + "]");
        }
        setState(STANDBY);
        network = "ok";
    }

    /** The user asked to leave (button, back key or the word "sair"): silence at once. */
    private void leave() {
        active = false;
        exitRequested = false;
        quit = true;
        finish();
    }

    /** The back key does not close this screen: only [ SAIR ] or asking him does. */
    @Override
    public void onBackPressed() {
        micText.setText("para sair: toque em [ SAIR ] ou peça a ele");
    }

    // ---- life cycle ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refreshData();
            main.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.postDelayed(tick, 15000);
        // coming back to the screen never turns the microphone on: that takes a tap
        listenOnResume = false;
        if (state == BOOT) maybeStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(tick);

        // leaving the screen turns the microphone off, whatever the reason
        boolean was = state == LISTENING;
        stopListening();
        if (was) {
            turnId++;
            listenOnResume = true;
            setState(STANDBY);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // nothing closes this screen but the user: it stays behind whatever was opened, like a home screen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        JarvisClient.cancel();

        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {}
            recognizer = null;
        }

        if (voice != null) {
            if (quit) voice.shutdown();
            else voice.shutdownWhenDone();
        }
        voice = null;
    }

    /** Grid and corner brackets behind everything. */
    private static class Backdrop extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int grid;
        int color;

        Backdrop(Context c, int grid) {
            super(c);
            this.grid = grid;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), step = 26 * d;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(grid);
            for (float x = step; x < w; x += step) canvas.drawLine(x, 0, x, h, p);
            for (float y = step; y < h; y += step) canvas.drawLine(0, y, w, y, p);

            p.setColor(color);
            p.setStrokeWidth(2 * d);
            float m = 10 * d, s = 16 * d;
            canvas.drawLine(m, m, m + s, m, p);
            canvas.drawLine(m, m, m, m + s, p);
            canvas.drawLine(w - m, m, w - m - s, m, p);
            canvas.drawLine(w - m, m, w - m, m + s, p);
            canvas.drawLine(m, h - m, m + s, h - m, p);
            canvas.drawLine(m, h - m, m, h - m - s, p);
            canvas.drawLine(w - m, h - m, w - m - s, h - m, p);
            canvas.drawLine(w - m, h - m, w - m, h - m - s, p);
        }
    }
}
