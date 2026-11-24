package ohi.andre.consolelauncher.managers.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
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

    private JarvisSession session;
    private final ScreenUi screen = new ScreenUi();
    private int shown = JarvisSession.BOOT;
    private boolean resumed, quit;

    /**
     * True while Jarvis is "on": from the moment his screen opens until the user leaves with the button or by
     * asking. While it is true the launcher sends the user back here, so this screen works as the home screen.
     * It lives only in memory: if the app process dies, the terminal is the home screen again.
     */
    public static volatile boolean active;

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
                if (session != null) session.tap();
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
        youText.setMinHeight(px(36));
        youText.setHint("toque para escrever · segure para adendos");
        youText.setHintTextColor(faint);
        View.OnClickListener write = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askTyped();
            }
        };
        youLabel.setOnClickListener(write);
        youText.setOnClickListener(write);
        View.OnLongClickListener notes = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                editAddenda();
                return true;
            }
        };
        youLabel.setOnLongClickListener(notes);
        youText.setOnLongClickListener(notes);
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

        active = true;
        session = JarvisSession.get(this);
        showState(JarvisSession.BOOT);

        // being called between midnight and five is not something he takes well
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5 && !JarvisClient.isOpen()) JarvisMood.nudge(this, 15, "foi acordado de madrugada");

        if (!session.hasMic() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_MIC) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) showNote("Toque no anel para falar.", false);
        else showNote("Sem o microfone o Jarvis não te ouve. Para liberar: Configurações > Apps > T-Lau > Permissões.", true);
    }

    /** Tap on his own line: a box to write the question instead of speaking it. */
    private void askTyped() {
        if (session == null) return;
        final EditText input = new EditText(this);
        input.setHint("escreva a pergunta");
        input.setSingleLine(false);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle(erick ? "Erick" : "Teetsuuo")
                .setView(input)
                .setPositiveButton("Enviar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (session != null) session.typed(input.getText().toString());
                    }
                })
                .setNeutralButton("Guardar como adendo", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (session == null) return;
                        String t = input.getText().toString().trim();
                        String old = session.addenda();
                        session.setAddenda(old.length() == 0 ? t : t.length() == 0 ? old : old + "\n" + t);
                        t = session.addenda();
                        showNote(t.length() == 0 ? "Adendos apagados." : "Adendo guardado. Vale a partir do próximo pedido.", false);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        input.requestFocus();
    }

    /** Long press on his own line: the notes, to write or erase. */
    private void editAddenda() {
        if (session == null) return;
        final EditText input = new EditText(this);
        input.setHint("ex.: o contato é Ezer (E-Z-E-R), nunca Wesley");
        input.setText(session.addenda());
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(6);
        new AlertDialog.Builder(this)
                .setTitle("Adendos")
                .setMessage("Notas que ajudam o Jarvis a te entender. Valem mais do que o que ele escuta. Deixe vazio para apagar.")
                .setView(input)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (session == null) return;
                        String t = input.getText().toString().trim();
                        session.setAddenda(t);
                        showNote(t.length() == 0 ? "Adendos apagados." : "Adendo guardado. Vale a partir do próximo pedido.", false);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
        input.requestFocus();
    }

    private boolean busy() {
        return shown == JarvisSession.THINKING || shown == JarvisSession.SPEAKING;
    }

    /** Small grey or amber line in the place of the answer (status, errors). */
    private void showNote(String s, boolean warn) {
        jarvisText.setTextColor(warn ? AMBER : dim);
        jarvisText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        jarvisText.setText(s);
    }

    private void showState(int s) {
        shown = s;
        int c;
        switch (s) {
            case JarvisSession.LISTENING:
                c = accent;
                hud.setState("OUVINDO", c, 1.2f, false, 1f, JarvisHudView.BARS_LEVEL);
                break;
            case JarvisSession.THINKING:
                c = AMBER;
                hud.setState("PENSANDO", c, 4f, true, 1f, JarvisHudView.BARS_FLAT);
                break;
            case JarvisSession.SPEAKING:
                c = WHITE;
                hud.setState("RESPONDENDO", c, 2f, false, 1f, JarvisHudView.BARS_TALK);
                break;
            case JarvisSession.STANDBY:
                c = dim;
                hud.setState("MUDO", c, 0.4f, false, 0.15f, JarvisHudView.BARS_FLAT);
                break;
            default:
                c = accent;
                hud.setState("INICIANDO", c, 0.6f, true, 1f, JarvisHudView.BARS_FLAT);
        }
        backdrop.color = c;
        backdrop.invalidate();

        // the screen stays awake only while a conversation is going on; at rest it may turn off like any home screen
        if (s == JarvisSession.LISTENING || busy()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String m;
        int mc;
        if (s == JarvisSession.STANDBY && JarvisAccess.instance == null) {
            m = "microfone desligado · toque no anel · bolinha desligada (-jarvis -acesso)";
            mc = dim;
        } else if (s == JarvisSession.LISTENING) {
            m = "microfone ligado · toque no anel para desligar";
            mc = accent;
        } else if (busy()) {
            m = "microfone desligado · toque no anel para interromper";
            mc = AMBER;
        } else {
            m = "microfone desligado · toque no anel para falar";
            mc = dim;
        }
        micText.setText(m);
        micText.setTextColor(mc);
        micDot.setTextColor(mc);
        refreshData();
    }

    /** How the conversation shows itself on the full screen. */
    class ScreenUi implements JarvisSession.Ui {
        @Override
        public void onState(int state) {
            showState(state);
        }

        @Override
        public void onUser(String text, boolean complete) {
            youText.setText(text);
            youText.setTextColor(complete ? dim : INK);
        }

        @Override
        public void onAnswer(String text) {
            jarvisText.setTextColor(INK);
            jarvisText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            jarvisText.setText(text);
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }

        @Override
        public void onNote(String text, boolean warn) {
            showNote(text, warn);
        }

        @Override
        public void onLevel(float level) {
            hud.setLevel(level);
        }

        @Override
        public void onNeedMic() {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        }

        @Override
        public void onData() {
            refreshData();
        }

        @Override
        public void onExit() {
            quit = true;
            active = false;
            finish();
        }
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
        String network = session == null ? "ok" : session.network();
        hud.setReadout(1, "REDE", network, "ok".equals(network) ? 0 : AMBER);
        long tokens = JarvisClient.contextTokens();
        hud.setReadout(2, "CONVERSA", tokens <= 0 ? "nova" : tokens < 1000 ? tokens + " tk" : String.format(Locale.US, "%.1fk tk", tokens / 1000.0), 0);
        boolean mic = session != null && session.micOn();
        hud.setReadout(3, "MIC", mic ? "on" : busy() ? "pausa" : "off", mic ? accent : busy() ? AMBER : dim);
    }

    // ---- life cycle ----

    /** The user asked to leave by the button: silence at once, and the terminal is the home screen again. */
    private void leave() {
        quit = true;
        active = false;
        JarvisSession s = JarvisSession.peek();
        if (s != null) s.close();
        finish();
    }

    /** The back key does not close this screen: only [ SAIR ] or asking him does. */
    @Override
    public void onBackPressed() {
        micText.setText("para sair: toque em [ SAIR ] ou peça a ele");
    }

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
        active = true;
        main.postDelayed(tick, 15000);

        // back on the full screen: the bubble, if it was out, comes home
        session = JarvisSession.get(this);
        JarvisAccess access = JarvisAccess.instance;
        if (access != null) {
            access.hideBubble();
            // lets the microphone work in the bubble later; it must start while this screen is in front
            if (session.hasMic() && !JarvisMicService.running) JarvisMicService.start(this);
        }
        session.attach(screen);

        // he was the one who called: he speaks first
        if (session.state() == JarvisSession.STANDBY && JarvisTriggers.hasPending(this)) session.answerNotice();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(tick);

        // without the bubble, leaving the screen turns the microphone off
        if (JarvisAccess.instance == null && session != null) session.mute();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (quit || isFinishing()) return;

        // nothing closes Jarvis but the user. With the accessibility service on, he follows as a bubble over whatever
        // was opened; without it, the conversation waits here, with the microphone off.
        JarvisSession s = JarvisSession.peek();
        if (s == null) return;
        JarvisAccess access = JarvisAccess.instance;
        if (access != null) {
            access.showBubble();
        } else {
            JarvisGuard.log(this, "bolinha: não mostrada (o serviço de acessibilidade está desligado; ligue com -jarvis -acesso)");
            s.mute();
            s.detach(screen);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        JarvisSession s = JarvisSession.peek();
        if (s != null) s.detach(screen);
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
