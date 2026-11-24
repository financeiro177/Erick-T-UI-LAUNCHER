package ohi.andre.consolelauncher.managers.ai;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * "Jarvis quer falar com você": a band at the top of the terminal, with [ ATENDER ] and [ DEPOIS ].
 * Answering opens his screen and he speaks first. Nothing is spent until then.
 */
public class JarvisNoticeCard {

    private static final int AMBER = 0xFFFFB454, DARK = 0xFF140D02, SOFT = 0xFFC9B18A;

    private static WeakReference<Activity> current;
    private static View shown;

    /** Called by the launcher in onResume. */
    public static void attach(Activity a) {
        current = new WeakReference<>(a);
        if (JarvisTriggers.hasPending(a) && !JarvisTriggers.snoozed(a) && !JarvisActivity.active) build(a);
    }

    /** Called by the launcher in onPause. */
    public static void detach(Activity a) {
        if (current != null && current.get() == a) current = null;
        remove();
    }

    /** True when the terminal is on screen and the band was shown. */
    static boolean show(Context c) {
        final Activity a = current == null ? null : current.get();
        if (a == null) return false;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                build(a);
            }
        });
        return true;
    }

    private static void remove() {
        View v = shown;
        shown = null;
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Button button(Context c, String label, boolean filled) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.MONOSPACE, filled ? Typeface.BOLD : Typeface.NORMAL);
        b.setTextSize(13);
        b.setTextColor(filled ? DARK : AMBER);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(filled ? AMBER : 0xFF000000);
        bg.setStroke(dp(c, 1), AMBER);
        b.setBackground(bg);
        return b;
    }

    private static void build(final Activity a) {
        try {
            if (shown != null && shown.getParent() != null) return;
            if (current == null || current.get() != a) return;

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
            box.setClickable(true);
            GradientDrawable border = new GradientDrawable();
            border.setColor(DARK);
            border.setStroke(dp(a, 1), AMBER);
            box.setBackground(border);

            TextView title = new TextView(a);
            title.setText("■ Jarvis quer falar com você");
            title.setTextColor(AMBER);
            title.setTextSize(15);
            title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            box.addView(title);

            TextView hint = new TextView(a);
            hint.setText("Ele só gasta crédito se você atender.");
            hint.setTextColor(SOFT);
            hint.setTextSize(12);
            hint.setTypeface(Typeface.MONOSPACE);
            hint.setPadding(0, dp(a, 4), 0, dp(a, 10));
            box.addView(hint);

            LinearLayout row = new LinearLayout(a);
            Button answer = button(a, "[ ATENDER ]", true), later = button(a, "[ DEPOIS ]", false);
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            la.rightMargin = dp(a, 8);
            row.addView(answer, la);
            row.addView(later, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            box.addView(row);

            answer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    remove();
                    JarvisActivity.open(a);
                }
            });
            later.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    JarvisTriggers.snooze(a, 30);
                    remove();
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            lp.setMargins(dp(a, 10), dp(a, 34), dp(a, 10), 0);
            ((ViewGroup) a.findViewById(android.R.id.content)).addView(box, lp);
            shown = box;
        } catch (Exception ignored) {}
    }
}
