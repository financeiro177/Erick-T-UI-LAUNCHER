package ohi.andre.consolelauncher.managers.reminder;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.List;

import ohi.andre.consolelauncher.tuils.Tuils;

/**
 * The reminder card: a full-screen overlay over the T-Lau home that only leaves when the user confirms
 * ("Ok", or "Ir" when it has an action) or snoozes it (+10 min, +1 h or a chosen time). Built in code, same look as the
 * approved mockup (black, terminal green, monospaced).
 */
public class ReminderCard {

    private static final int GREEN = 0xFF39D353;
    private static final int GREEN_LINE = 0xFF1F5A2B;
    private static final int GREEN_DIM = 0xFF2F7A3D;
    private static final int TEXT = 0xFFE8FFE8;
    private static final int BODY = 0xFFA9D8B0;
    private static final int INK = 0xFF001A06;

    private static WeakReference<Activity> current;
    private static View shown;

    /** Called by the launcher in onResume: from now on cards can be shown, and due ones are shown now. */
    public static void attach(Activity a) {
        current = new WeakReference<>(a);
        ReminderScheduler.rescheduleAll(a, false);
        showDue(a);
    }

    /** Called by the launcher in onPause. */
    public static void detach(Activity a) {
        if (current != null && current.get() == a) current = null;
        removeView();
    }

    /** True when the T-Lau is on screen and took care of showing the card. */
    public static boolean onDue(Context c) {
        final Activity a = current == null ? null : current.get();
        if (a == null) return false;

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                showDue(a);
            }
        });
        return true;
    }

    private static void removeView() {
        View v = shown;
        shown = null;
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private static boolean visible() {
        return shown != null && shown.getParent() != null;
    }

    public static void showDue(Activity a) {
        try {
            if (visible()) return;
            if (current == null || current.get() != a) return;

            List<ReminderStore.Reminder> due = ReminderStore.due(a, System.currentTimeMillis());
            if (due.isEmpty()) return;

            build(a, due.get(0), due.size());
        } catch (Exception ignored) {}
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView text(Context c, String s, int size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private static Button button(Context c, String label, boolean filled) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextSize(13);
        b.setTextColor(filled ? INK : GREEN);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 4), 0, dp(c, 4), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? GREEN : Color.BLACK);
        bg.setStroke(dp(c, 1), GREEN);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x4439D353), bg, null));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private static void build(final Activity a, final ReminderStore.Reminder r, int dueCount) {
        FrameLayout scrim = new FrameLayout(a);
        scrim.setBackgroundColor(0xE6000000);
        scrim.setClickable(true);
        scrim.setFocusable(true);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(dp(a, 1), GREEN);
        card.setBackground(border);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardParams.setMargins(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        scrim.addView(card, cardParams);

        // header
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
        TextView name = text(a, "[ LEMBRETE ]", 12, GREEN);
        name.setLetterSpacing(0.08f);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(text(a, ReminderStore.hour(r.when), 12, GREEN));
        card.addView(header);

        View line = new View(a);
        line.setBackgroundColor(GREEN_LINE);
        card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));

        // content
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 10));

        TextView title = text(a, r.title, 17, TEXT);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        content.addView(title);

        if (r.body.length() > 0) {
            TextView body = text(a, r.body, 13, BODY);
            body.setPadding(0, dp(a, 6), 0, 0);
            content.addView(body);
        }

        if (r.hasAction()) {
            TextView action = text(a, "Ir -> " + ReminderAction.describe(r), 12, GREEN);
            action.setPadding(0, dp(a, 8), 0, 0);
            content.addView(action);

            if (r.actionText.length() > 0) {
                TextView message = text(a, "\"" + r.actionText + "\"", 12, BODY);
                message.setPadding(0, dp(a, 4), 0, 0);
                message.setMaxLines(6);
                content.addView(message);
            }
        }

        String meta = "criado por " + (ReminderStore.SOURCE_AI.equals(r.source) ? "ai" : "você") + " às " + ReminderStore.hour(r.created);
        if (dueCount > 1) meta += " · 1 de " + dueCount + " na fila";
        TextView metaView = text(a, meta, 11, GREEN_DIM);
        metaView.setPadding(0, dp(a, 10), 0, 0);
        content.addView(metaView);
        card.addView(content);

        // buttons
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 12));

        // a reminder with an action gets "Ir" (does it) next to "Ok" (just closes); a plain one only "Ok"
        final Button go = r.hasAction() ? button(a, "Ir", true) : null;
        Button ok = button(a, "Ok", go == null);

        if (go == null) {
            buttons.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
            goParams.rightMargin = dp(a, 8);
            top.addView(go, goParams);
            top.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            buttons.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String problem = ReminderAction.perform(a, r);
                    if (problem == null) confirm(a, r);
                    else Tuils.sendOutput(Color.YELLOW, a, "lembrete: " + problem + ". O cartão continua aberto.");
                }
            });
        }

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(a, 8);
        buttons.addView(row, rowParams);

        Button b10 = button(a, "+10 min", false);
        Button b60 = button(a, "+1 h", false);
        Button bAt = button(a, "Hora…", false);
        for (Button b : new Button[]{b10, b60, bAt}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            p.rightMargin = b == bAt ? 0 : dp(a, 8);
            row.addView(b, p);
        }
        card.addView(buttons);

        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm(a, r);
            }
        });
        b10.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                snooze(a, r, System.currentTimeMillis() + 10 * 60 * 1000L);
            }
        });
        b60.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                snooze(a, r, System.currentTimeMillis() + 60 * 60 * 1000L);
            }
        });
        bAt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar now = Calendar.getInstance();
                new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hour, int minute) {
                        snooze(a, r, nextOccurrence(hour, minute));
                    }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
            }
        });

        ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shown = scrim;

        // the T-Lau keeps the keyboard open; the card needs the whole screen
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(scrim.getWindowToken(), 0);
        } catch (Exception ignored) {}

        ReminderReceiver.cancelNotification(a, r.id);
    }

    /** The next time the clock reads hour:minute (today if still ahead, otherwise tomorrow). */
    public static long nextOccurrence(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static void confirm(Activity a, ReminderStore.Reminder r) {
        ReminderStore.remove(a, r.id);
        ReminderScheduler.cancel(a, r.id);
        ReminderReceiver.cancelNotification(a, r.id);
        removeView();
        Tuils.sendOutput(Color.CYAN, a, "lembrete confirmado: " + r.title);
        showDue(a);
    }

    private static void snooze(Activity a, ReminderStore.Reminder r, long when) {
        ReminderStore.Reminder moved = ReminderStore.snooze(a, r.id, when);
        if (moved != null) ReminderScheduler.schedule(a, moved);
        ReminderReceiver.cancelNotification(a, r.id);
        removeView();
        Tuils.sendOutput(Color.CYAN, a, "lembrete adiado para " + ReminderStore.label(when) + ": " + r.title);
        showDue(a);
    }
}
