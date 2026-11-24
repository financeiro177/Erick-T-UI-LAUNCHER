package ohi.andre.consolelauncher.managers.ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * The core of the Jarvis screen: layered rings that turn, a gauge, the mark of the profile in the centre,
 * sound bars and four small read-outs in the corners. All drawn here, no images.
 */
public class JarvisHudView extends View {

    public static final int BARS_FLAT = 0, BARS_LEVEL = 1, BARS_TALK = 2;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float dp;

    private int color = 0xFF3DFF8F;
    private int dim = 0xFF8DB89D;
    private int track = 0xFF123321;
    private int faint = 0xFF1A4A2F;
    private int ground = 0xFF030604;

    private String mark = "T>_";
    private String state = "";
    private float spin = 1f;        // turns of the outer ring per minute
    private boolean sweeping;       // the gauge runs around instead of standing full
    private float gauge = 1f;
    private int bars = BARS_FLAT;
    private float level;            // 0..1, loudness of the microphone
    private float shownLevel;

    private final String[] labels = {"", "", "", ""};
    private final String[] values = {"", "", "", ""};
    private final int[] valueColors = {0, 0, 0, 0};

    private static final float[] BAR_SHAPE = {0.30f, 0.55f, 0.85f, 0.50f, 1.00f, 0.70f, 0.40f, 0.80f, 0.55f, 0.25f};

    public JarvisHudView(Context context, Typeface font) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        if (font != null) text.setTypeface(font);
    }

    public void setPalette(int color, int dim, int track, int faint, int ground) {
        this.dim = dim;
        this.track = track;
        this.faint = faint;
        this.ground = ground;
        this.color = color;
        invalidate();
    }

    public void setMark(String mark) {
        this.mark = mark;
    }

    public void setState(String label, int color, float spin, boolean sweeping, float gauge, int bars) {
        this.state = label;
        this.color = color;
        this.spin = spin;
        this.sweeping = sweeping;
        this.gauge = gauge;
        this.bars = bars;
        invalidate();
    }

    public void setLevel(float level) {
        this.level = Math.max(0f, Math.min(1f, level));
    }

    /** corner: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. valueColor 0 = the normal text colour. */
    public void setReadout(int corner, String label, String value, int valueColor) {
        labels[corner] = label;
        values[corner] = value;
        valueColors[corner] = valueColor;
    }

    private void arc(Canvas canvas, float cx, float cy, float r, float start, float sweep) {
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, start, sweep, false, line);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) / 2f - 4 * dp;

        // crosshair and the scan line
        line.setColor(faint);
        line.setStrokeWidth(dp);
        line.setAlpha(255);
        canvas.drawLine(0, cy, w, cy, line);
        canvas.drawLine(cx, 0, cx, h, line);

        float scan = (now % 5000L) / 5000f * h;
        fill.setColor(color);
        fill.setAlpha(55);
        canvas.drawRect(0, scan, w, scan + 2 * dp, fill);

        // ruler of ticks
        line.setColor(color);
        line.setAlpha(200);
        line.setStrokeWidth(1.2f * dp);
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5);
            float len = (i % 6 == 0 ? 7 : 4) * dp;
            canvas.drawLine(cx + (float) Math.cos(a) * (R - len), cy + (float) Math.sin(a) * (R - len),
                    cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, line);
        }

        // outer arcs, turning one way
        float rot = (now % 600000L) / 60000f * 360f * spin;
        line.setAlpha(255);
        line.setStrokeWidth(4 * dp);
        float r1 = R * 0.86f;
        arc(canvas, cx, cy, r1, rot, 150);
        arc(canvas, cx, cy, r1, rot + 185, 30);
        arc(canvas, cx, cy, r1, rot + 250, 70);

        line.setStrokeWidth(dp);
        line.setAlpha(150);
        arc(canvas, cx, cy, R * 0.79f, 0, 360);

        // inner arcs, turning the other way
        float back = -rot * 1.5f;
        line.setStrokeWidth(1.6f * dp);
        line.setAlpha(180);
        float r2 = R * 0.72f;
        for (int i = 0; i < 6; i++) {
            arc(canvas, cx, cy, r2, back + i * 60, 40);
            arc(canvas, cx, cy, r2, back + i * 60 + 48, 5);
        }

        // gauge
        float r3 = R * 0.61f;
        line.setStrokeWidth(9 * dp);
        line.setColor(track);
        line.setAlpha(255);
        arc(canvas, cx, cy, r3, 0, 360);
        line.setColor(color);
        if (sweeping) {
            arc(canvas, cx, cy, r3, (now % 1400L) / 1400f * 360f, 130);
        } else {
            arc(canvas, cx, cy, r3, -90, 360 * gauge);
        }

        // two small pointers on the gauge
        fill.setColor(color);
        fill.setAlpha(255);
        canvas.save();
        canvas.rotate(back, cx, cy);
        canvas.drawRect(cx - 3 * dp, cy - r3 - 12 * dp, cx + 3 * dp, cy - r3 - 6 * dp, fill);
        canvas.drawRect(cx - 3 * dp, cy + r3 + 6 * dp, cx + 3 * dp, cy + r3 + 12 * dp, fill);
        canvas.restore();

        // centre: mark, bars, state
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setColor(color);
        text.setTextSize(22 * dp);
        canvas.drawText(mark, cx, cy - 16 * dp, text);

        shownLevel += (level - shownLevel) * 0.25f;
        float barW = 3.5f * dp, gap = 3.5f * dp, maxH = 26 * dp;
        float total = BAR_SHAPE.length * barW + (BAR_SHAPE.length - 1) * gap;
        float x = cx - total / 2f, mid = cy + 8 * dp;
        for (int i = 0; i < BAR_SHAPE.length; i++) {
            float amount;
            if (bars == BARS_FLAT) {
                amount = 0.08f;
            } else if (bars == BARS_LEVEL) {
                amount = 0.10f + BAR_SHAPE[i] * shownLevel * (0.75f + 0.25f * (float) Math.sin(now / 90.0 + i));
            } else {
                amount = BAR_SHAPE[i] * (0.55f + 0.45f * (float) Math.sin(now / (110.0 + i * 13) + i * 1.7));
            }
            float bh = Math.max(2 * dp, maxH * Math.max(0f, Math.min(1f, amount)));
            canvas.drawRect(x, mid - bh / 2f, x + barW, mid + bh / 2f, fill);
            x += barW + gap;
        }

        text.setTextSize(11 * dp);
        canvas.drawText(state, cx, cy + 40 * dp, text);
        text.setFakeBoldText(false);

        // read-outs in the corners
        for (int i = 0; i < 4; i++) {
            if (labels[i].length() == 0 && values[i].length() == 0) continue;
            boolean left = i % 2 == 0, top = i < 2;

            text.setTextAlign(left ? Paint.Align.LEFT : Paint.Align.RIGHT);
            float tx = left ? 0 : w;
            float labelY = top ? 11 * dp : h - 17 * dp;
            float valueY = labelY + 14 * dp;

            text.setTextSize(12 * dp);
            float width = Math.max(text.measureText(values[i]), text.measureText(labels[i])) + 8 * dp;
            fill.setColor(ground);
            fill.setAlpha(255);
            canvas.drawRect(left ? 0 : w - width, labelY - 11 * dp, left ? width : w, valueY + 4 * dp, fill);

            text.setTextSize(9.5f * dp);
            text.setColor(dim);
            canvas.drawText(labels[i], tx, labelY, text);
            text.setTextSize(12 * dp);
            text.setColor(valueColors[i] == 0 ? 0xFFE9FFF1 : valueColors[i]);
            canvas.drawText(values[i], tx, valueY, text);
        }

        postInvalidateOnAnimation();
    }
}
