package ohi.andre.consolelauncher.managers.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The eyes and the finger of Jarvis: an accessibility service, which the user turns on by hand in the Android
 * settings. It reads what is written on the screen, taps, types and scrolls, always inside the limits of
 * JarvisGuard, and it draws the "mini Jarvis": a small round bubble over the other apps that shows what he is
 * doing and takes a tap to talk or to stop him.
 *
 * It does nothing by itself: every action here is called by a tool, during a conversation the user started.
 */
public class JarvisAccess extends AccessibilityService {

    public static volatile JarvisAccess instance;

    private static final int MAX_NODES = 140;
    private static final int MAX_TEXT = 90;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    private CharSequence lastPackage;

    @Override
    protected void onServiceConnected() {
        instance = this;
        JarvisGuard.log(this, "serviço de acessibilidade ligado");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        hideBubble();
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideBubble();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean on() {
        return instance != null;
    }

    public static final String OFF = "O serviço de acessibilidade do Jarvis está desligado, então você não enxerga nem toca na tela. "
            + "Diga a ele para ligar com o comando -jarvis -acesso no terminal.";

    // ---- reading ----

    private String appLabel(CharSequence pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.toString(), 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return String.valueOf(pkg);
        }
    }

    private static String shortText(CharSequence s) {
        if (s == null) return "";
        String t = s.toString().replaceAll("\\s+", " ").trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "…" : t;
    }

    private static String label(AccessibilityNodeInfo n) {
        String t = shortText(n.getText());
        if (t.length() == 0) t = shortText(n.getContentDescription());
        if (t.length() == 0 && Build.VERSION.SDK_INT >= 26) t = shortText(n.getHintText());
        return t;
    }

    private void forget() {
        for (AccessibilityNodeInfo n : lastNodes) {
            try {
                n.recycle();
            } catch (Exception ignored) {}
        }
        lastNodes.clear();
    }

    /** The screen as a numbered list. The numbers are what tocar and digitar take. */
    public synchronized String dump() {
        forget();

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "TELA: não consegui ler (a tela pode estar apagada, bloqueada ou trocando de app). Use esperar e tente de novo.";

        lastPackage = root.getPackageName();
        if (JarvisGuard.isBlocked(this, lastPackage)) {
            JarvisGuard.log(this, "recusou ler app protegido: " + lastPackage);
            return "TELA: APP PROTEGIDO (" + appLabel(lastPackage) + "). Você não lê nem toca neste app. "
                    + "Diga a ele que aqui é ele quem mexe, e espere ele avisar que terminou.";
        }

        StringBuilder b = new StringBuilder("TELA do app ").append(appLabel(lastPackage)).append(" (").append(lastPackage).append("):");
        boolean[] password = {false};
        walk(root, b, password, 0);

        if (lastNodes.isEmpty()) b.append("\n(nada legível: pode ser vídeo, jogo, mapa ou imagem)");
        if (password[0]) {
            b.append("\nHÁ CAMPO DE SENHA: você não lê nem preenche senha. Pare, peça em voz alta para ele digitar, "
                    + "e só continue quando ele disser que terminou.");
        }
        return b.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder b, boolean[] password, int depth) {
        if (n == null || lastNodes.size() >= MAX_NODES || depth > 40) return;

        try {
            if (n.isVisibleToUser()) {
                boolean secret = n.isPassword();
                String text = secret ? "" : label(n);
                boolean acts = n.isClickable() || n.isEditable() || n.isScrollable() || n.isCheckable() || n.isLongClickable();

                if (secret || text.length() > 0 || acts) {
                    int index = lastNodes.size();
                    lastNodes.add(AccessibilityNodeInfo.obtain(n));

                    String type = n.getClassName() == null ? "" : n.getClassName().toString();
                    type = type.substring(type.lastIndexOf('.') + 1);

                    b.append("\n[").append(index).append("] ");
                    if (secret) {
                        password[0] = true;
                        b.append("CAMPO DE SENHA (oculto)");
                    } else {
                        b.append(type);
                        if (text.length() > 0) b.append(" \"").append(text).append('"');
                        if (n.isEditable()) b.append(" (campo de texto)");
                        if (n.isClickable()) b.append(" (toque)");
                        if (n.isScrollable()) b.append(" (rola)");
                        if (n.isCheckable()) b.append(n.isChecked() ? " (marcado)" : " (desmarcado)");
                        if (!n.isEnabled()) b.append(" (desativado)");
                    }
                }
            }

            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), b, password, depth + 1);
        } catch (Exception ignored) {}
    }

    private String guardNow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root == null ? lastPackage : root.getPackageName();
        if (JarvisGuard.isBlocked(this, pkg)) return "APP PROTEGIDO: você não toca neste app. Peça para ele mesmo fazer.";
        return null;
    }

    private AccessibilityNodeInfo node(int index) {
        return index < 0 || index >= lastNodes.size() ? null : lastNodes.get(index);
    }

    // ---- acting ----

    public synchronized String tap(int index) {
        String stop = guardNow();
        if (stop != null) return stop;

        AccessibilityNodeInfo n = node(index);
        if (n == null) return "erro: não existe o item " + index + " na última tela lida. Use ver_tela de novo.";
        if (n.isPassword()) return "erro: é um campo de senha. Peça para ele digitar e espere.";

        String label = label(n);
        if (label.length() == 0) label = "item " + index;

        if (JarvisGuard.isRisky(label)) {
            String ask = JarvisGuard.needsConfirmation(label);
            if (ask != null) {
                JarvisGuard.log(this, "pediu confirmação antes de tocar em \"" + label + "\" (" + lastPackage + ")");
                return ask;
            }
        }

        boolean done = false;
        try {
            AccessibilityNodeInfo target = n;
            for (int up = 0; target != null && up < 6; up++) {
                if (target.isClickable()) {
                    done = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                target = target.getParent();
            }
            if (!done) done = gestureTap(n);
        } catch (Exception e) {
            done = false;
        }

        JarvisGuard.log(this, (done ? "tocou em \"" : "tentou tocar em \"") + label + "\" (" + lastPackage + ")");
        return done ? "ok, toquei em \"" + label + "\". Use ver_tela para ver o que mudou."
                : "não consegui tocar em \"" + label + "\". Leia a tela de novo e tente outro item.";
    }

    private boolean gestureTap(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    public synchronized String type(int index, String text) {
        String stop = guardNow();
        if (stop != null) return stop;

        AccessibilityNodeInfo n = node(index);
        if (n == null) return "erro: não existe o item " + index + " na última tela lida. Use ver_tela de novo.";
        if (n.isPassword()) {
            JarvisGuard.log(this, "recusou digitar em campo de senha (" + lastPackage + ")");
            return "RECUSADO: é um campo de senha. Você nunca digita senha. Peça para ele digitar e espere ele avisar.";
        }
        if (!n.isEditable()) return "erro: o item " + index + " não é um campo de texto.";

        boolean done;
        try {
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
            done = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            done = false;
        }

        JarvisGuard.log(this, (done ? "escreveu \"" : "tentou escrever \"") + shortText(text) + "\" (" + lastPackage + ")");
        return done ? "ok, escrevi. Use ver_tela para conferir e achar o botão seguinte." : "não consegui escrever nesse campo.";
    }

    public synchronized String scroll(String direction) {
        String stop = guardNow();
        if (stop != null) return stop;

        boolean up = direction != null && direction.toLowerCase(Locale.ROOT).startsWith("c");   // "cima"
        boolean done = false;
        try {
            for (AccessibilityNodeInfo n : lastNodes) {
                if (!n.isScrollable()) continue;
                done = n.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (done) break;
            }
            if (!done && Build.VERSION.SDK_INT >= 24) {
                int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
                Path p = new Path();
                p.moveTo(w / 2f, up ? h * 0.35f : h * 0.70f);
                p.lineTo(w / 2f, up ? h * 0.70f : h * 0.35f);
                done = dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
            }
        } catch (Exception e) {
            done = false;
        }

        JarvisGuard.log(this, "rolou para " + (up ? "cima" : "baixo") + " (" + lastPackage + ")");
        return done ? "ok, rolei. Use ver_tela para ler o que apareceu." : "não havia o que rolar.";
    }

    public String press(String key) {
        key = key == null ? "" : key.toLowerCase(Locale.ROOT);
        int action = key.startsWith("in") || key.equals("home") ? GLOBAL_ACTION_HOME
                : key.startsWith("rec") ? GLOBAL_ACTION_RECENTS
                : key.startsWith("not") ? GLOBAL_ACTION_NOTIFICATIONS
                : GLOBAL_ACTION_BACK;
        boolean done = performGlobalAction(action);
        JarvisGuard.log(this, "apertou " + (action == GLOBAL_ACTION_HOME ? "início" : action == GLOBAL_ACTION_RECENTS ? "recentes"
                : action == GLOBAL_ACTION_NOTIFICATIONS ? "notificações" : "voltar"));
        return done ? "ok. Use ver_tela para ver onde está." : "não consegui.";
    }

    // ---- the mini Jarvis ----

    private LinearLayout bubble;
    private Dot dot;
    private TextView caption;
    private WindowManager.LayoutParams where;
    private JarvisSession.Ui ui;

    private int px(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Shows the bubble over whatever app is in front and makes it the face of the conversation. */
    public void showBubble() {
        main.post(new Runnable() {
            @Override
            public void run() {
                final JarvisSession session = JarvisSession.peek();
                if (bubble != null) return;
                if (session == null) {
                    JarvisGuard.log(JarvisAccess.this, "bolinha: não mostrada (não há conversa aberta)");
                    return;
                }

                try {
                    WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

                    bubble = new LinearLayout(JarvisAccess.this);
                    bubble.setGravity(Gravity.CENTER_VERTICAL);

                    dot = new Dot(JarvisAccess.this);
                    bubble.addView(dot, new LinearLayout.LayoutParams(px(58), px(58)));

                    caption = new TextView(JarvisAccess.this);
                    caption.setTextColor(0xFFE9FFF1);
                    caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    caption.setTypeface(Typeface.MONOSPACE);
                    caption.setMaxWidth(px(230));
                    caption.setMaxLines(4);
                    caption.setPadding(px(10), px(6), px(10), px(6));
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xE6030604);
                    bg.setStroke(px(1), 0xFF1A4A2F);
                    bg.setCornerRadius(px(6));
                    caption.setBackground(bg);
                    caption.setVisibility(View.GONE);
                    LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lc.leftMargin = px(6);
                    bubble.addView(caption, lc);

                    where = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    where.gravity = Gravity.TOP | Gravity.START;
                    where.x = px(12);
                    where.y = px(120);

                    dot.setOnTouchListener(new Drag(wm));
                    wm.addView(bubble, where);

                    ui = new BubbleUi();
                    session.attach(ui);
                    JarvisGuard.log(JarvisAccess.this, "bolinha: mostrada");
                } catch (Exception e) {
                    bubble = null;
                    JarvisGuard.log(JarvisAccess.this, "bolinha: FALHOU ao mostrar: " + e);
                }
            }
        });
    }

    public void hideBubble() {
        main.post(new Runnable() {
            @Override
            public void run() {
                JarvisSession session = JarvisSession.peek();
                if (session != null && ui != null) session.detach(ui);
                ui = null;

                if (bubble != null) {
                    try {
                        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(bubble);
                    } catch (Exception ignored) {}
                }
                bubble = null;
                dot = null;
                caption = null;
            }
        });
    }

    public boolean bubbleShown() {
        return bubble != null;
    }

    private void say(String text) {
        if (caption == null) return;
        text = text == null ? "" : text.trim();
        if (text.length() > 150) text = "…" + text.substring(text.length() - 150);
        caption.setText(text);
        caption.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private class BubbleUi implements JarvisSession.Ui {
        @Override
        public void onState(int state) {
            if (dot == null) return;
            dot.state = state;
            dot.invalidate();
            if (state == JarvisSession.LISTENING) say("ouvindo…");
            else if (state == JarvisSession.THINKING) say("pensando…");
        }

        @Override
        public void onUser(String text, boolean complete) {
            if (text.length() > 0) say("você: " + text);
        }

        @Override
        public void onAnswer(String text) {
            say(text);
        }

        @Override
        public void onNote(String text, boolean warn) {
            say(text);
        }

        @Override
        public void onLevel(float level) {
            if (dot != null) dot.level = level;
        }

        @Override
        public void onNeedMic() {
            say("sem permissão de microfone: abra a tela do Jarvis");
        }

        @Override
        public void onData() {}

        @Override
        public void onExit() {
            hideBubble();
        }
    }

    /** Drag moves the bubble; a tap talks or stops; a long press opens the full Jarvis screen. */
    private class Drag implements View.OnTouchListener {
        private final WindowManager wm;
        private float downX, downY;
        private int startX, startY;
        private long downAt;
        private boolean moved;

        Drag(WindowManager wm) {
            this.wm = wm;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = where.x;
                    startY = where.y;
                    downAt = SystemClock.uptimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > px(8) || Math.abs(dy) > px(8)) moved = true;
                    if (moved && bubble != null) {
                        where.x = startX + (int) dx;
                        where.y = startY + (int) dy;
                        try {
                            wm.updateViewLayout(bubble, where);
                        } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved) return true;
                    if (SystemClock.uptimeMillis() - downAt > 600) {
                        JarvisActivity.open(JarvisAccess.this);
                    } else {
                        JarvisSession session = JarvisSession.peek();
                        if (session != null) session.tap();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** The rings of the big screen, in small. */
    private static class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        int state = JarvisSession.STANDBY;
        float level;

        Dot(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f - 3;
            float d = getResources().getDisplayMetrics().density;
            long now = SystemClock.uptimeMillis();

            int color = state == JarvisSession.LISTENING ? 0xFF3DFF8F : state == JarvisSession.THINKING ? 0xFFFFB454
                    : state == JarvisSession.SPEAKING ? 0xFFF2FFF6 : 0xFF8DB89D;

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF0030604);
            canvas.drawCircle(cx, cy, r, p);

            p.setStyle(Paint.Style.STROKE);
            p.setColor(color);
            p.setStrokeWidth(2.5f * d);
            float turn = state == JarvisSession.STANDBY ? 0 : (now % 3000L) / 3000f * 360f * (state == JarvisSession.THINKING ? 2 : 1);
            oval.set(cx - r + 2 * d, cy - r + 2 * d, cx + r - 2 * d, cy + r - 2 * d);
            canvas.drawArc(oval, turn, 200, false, p);
            canvas.drawArc(oval, turn + 230, 90, false, p);

            p.setStyle(Paint.Style.FILL);
            float core = r * (0.30f + 0.25f * (state == JarvisSession.LISTENING ? Math.min(1f, level)
                    : state == JarvisSession.SPEAKING ? 0.5f + 0.5f * (float) Math.sin(now / 120.0) : 0f));
            canvas.drawCircle(cx, cy, core, p);

            if (state != JarvisSession.STANDBY) postInvalidateOnAnimation();
        }
    }

    // ---- turning it on ----

    public static void openSettings(Context c) {
        android.content.Intent i = new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
