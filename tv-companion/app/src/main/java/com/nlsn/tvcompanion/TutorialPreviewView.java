package com.nlsn.tvcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Draws a lightweight, screenshot-style walkthrough without embedding Google Play
 * screenshots. The visuals intentionally resemble the real flow while remaining
 * stable across Play Store and TV firmware versions.
 */
public final class TutorialPreviewView extends View {

    private static final int STEP_COUNT = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int step;

    public TutorialPreviewView(Context context) {
        super(context);
        init();
    }

    public TutorialPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.NORMAL));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setStep(int value) {
        step = Math.max(0, Math.min(STEP_COUNT - 1, value));
        invalidate();
    }

    public int getStep() {
        return step;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = dp(360);
        }
        int desiredHeight = Math.round(width * 0.62f);
        int height;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(desiredHeight, heightSize);
        } else {
            height = desiredHeight;
        }
        setMeasuredDimension(width, Math.max(dp(190), height));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        canvas.drawColor(Color.rgb(238, 242, 248));

        if (step <= 2) {
            drawPlayStoreFlow(canvas, w, h, step);
        } else if (step == 3) {
            drawTvHome(canvas, w, h);
        } else if (step == 4) {
            drawTvApps(canvas, w, h);
        } else {
            drawTvActivation(canvas, w, h);
        }
    }

    private void drawPlayStoreFlow(Canvas canvas, float w, float h, int state) {
        float s = Math.min(w / 1000f, h / 620f);
        float ox = (w - 1000f * s) / 2f;
        float oy = (h - 620f * s) / 2f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(s, s);

        fill(Color.rgb(100, 104, 111));
        canvas.drawRoundRect(new RectF(160, 16, 840, 606), 42, 42, paint);

        fill(Color.rgb(248, 249, 251));
        canvas.drawRoundRect(new RectF(176, 30, 824, 592), 36, 36, paint);

        fill(Color.rgb(233, 236, 241));
        canvas.drawRect(176, 30, 824, 120, paint);
        drawText(canvas, "For you", 212, 82, 24, Color.rgb(28, 93, 180), true);
        drawText(canvas, "Top charts", 350, 82, 24, Color.rgb(55, 58, 65), false);
        drawText(canvas, "Other devices", 520, 82, 24, Color.rgb(55, 58, 65), false);

        fill(Color.WHITE);
        canvas.drawRoundRect(new RectF(176, 104, 824, 592), 38, 38, paint);

        drawText(canvas, "Google Play", 214, 160, 32, Color.rgb(62, 65, 72), false);
        drawText(canvas, "×", 770, 160, 40, Color.rgb(62, 65, 72), false);

        drawAppIcon(canvas, 222, 194, 108);
        drawText(canvas, "ConfluenceTV", 356, 232, 40, Color.rgb(28, 30, 34), false);
        drawText(canvas, "Nielsen Streaming Panel", 356, 272, 22, Color.rgb(31, 92, 174), true);

        drawText(canvas, "4.5 ★", 254, 332, 24, Color.rgb(30, 32, 35), true);
        drawText(canvas, "Rated for 3+", 450, 332, 22, Color.rgb(58, 61, 68), false);
        drawText(canvas, "10K+ downloads", 628, 332, 22, Color.rgb(58, 61, 68), false);

        fill(Color.rgb(255, 244, 181));
        canvas.drawRoundRect(new RectF(220, 360, 780, 430), 18, 18, paint);
        drawText(canvas, "ⓘ  Available for your other devices", 248, 403, 22,
                Color.rgb(117, 75, 18), false);

        if (state == 0) {
            drawText(canvas, "Installed on all devices", 222, 490, 28,
                    Color.rgb(34, 36, 40), false);
            drawCircleButton(canvas, 742, 478, false);
            drawCallout(canvas, 36, 172, 300, 280, "1", "Tap the arrow",
                    "Open the TV device list");
        } else {
            drawText(canvas, "Choose a device", 222, 472, 28,
                    Color.rgb(34, 36, 40), false);
            drawCircleButton(canvas, 742, 463, true);
            drawDeviceRow(canvas, 222, 492, "Sony BRAVIA", state == 2);
            drawDeviceRow(canvas, 222, 548, "Xiaomi MiTV", false);

            if (state == 1) {
                drawCallout(canvas, 32, 174, 306, 292, "2", "Select your TV",
                        "Eligible TVs appear here");
            } else {
                stroke(Color.rgb(43, 113, 232), 5);
                canvas.drawRoundRect(new RectF(212, 482, 790, 542), 16, 16, paint);
                drawCallout(canvas, 30, 168, 314, 302, "3", "Confirm Install",
                        "Google Play starts the TV install");
            }
        }
        canvas.restore();
    }

    private void drawDeviceRow(Canvas canvas, float x, float y, String name, boolean selected) {
        fill(selected ? Color.rgb(235, 244, 255) : Color.rgb(248, 249, 251));
        canvas.drawRoundRect(new RectF(x, y, 790, y + 50), 14, 14, paint);

        fill(Color.rgb(225, 228, 233));
        canvas.drawCircle(x + 28, y + 25, 20, paint);
        stroke(Color.rgb(70, 74, 82), 3);
        canvas.drawRect(x + 17, y + 15, x + 39, y + 31, paint);
        canvas.drawLine(x + 25, y + 36, x + 34, y + 36, paint);

        drawText(canvas, name, x + 58, y + 31, 21, Color.rgb(35, 38, 43), false);
        fill(Color.rgb(35, 102, 210));
        canvas.drawRoundRect(new RectF(650, y + 7, 780, y + 43), 18, 18, paint);
        drawText(canvas, "Install", 681, y + 32, 20, Color.WHITE, true);
    }

    private void drawCircleButton(Canvas canvas, float cx, float cy, boolean up) {
        fill(Color.rgb(235, 238, 243));
        canvas.drawCircle(cx, cy, 27, paint);
        stroke(Color.rgb(62, 66, 73), 4);
        path.reset();
        if (up) {
            path.moveTo(cx - 9, cy + 4);
            path.lineTo(cx, cy - 5);
            path.lineTo(cx + 9, cy + 4);
        } else {
            path.moveTo(cx - 9, cy - 4);
            path.lineTo(cx, cy + 5);
            path.lineTo(cx + 9, cy - 4);
        }
        canvas.drawPath(path, paint);
    }

    private void drawCallout(
            Canvas canvas,
            float left,
            float top,
            float right,
            float bottom,
            String number,
            String title,
            String body) {
        paint.setShadowLayer(14, 0, 8, Color.argb(50, 0, 0, 0));
        fill(Color.WHITE);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 22, 22, paint);
        paint.clearShadowLayer();
        fill(Color.rgb(38, 107, 224));
        canvas.drawCircle(left + 42, top + 42, 24, paint);
        drawText(canvas, number, left + 35, top + 51, 25, Color.WHITE, true);
        drawText(canvas, title, left + 80, top + 43, 25, Color.rgb(27, 39, 56), true);
        drawText(canvas, body, left + 28, top + 86, 19, Color.rgb(91, 102, 119), false);
    }

    private void drawTvHome(Canvas canvas, float w, float h) {
        beginTv(canvas, w, h);
        drawText(canvas, "Google TV", 80, 74, 30, Color.WHITE, true);
        drawText(canvas, "For you", 80, 128, 23, Color.rgb(170, 183, 202), false);
        drawText(canvas, "Movies", 220, 128, 23, Color.rgb(170, 183, 202), false);
        drawText(canvas, "Shows", 350, 128, 23, Color.rgb(170, 183, 202), false);
        drawText(canvas, "Apps", 475, 128, 24, Color.WHITE, true);
        fill(Color.rgb(62, 123, 255));
        canvas.drawRoundRect(new RectF(474, 142, 548, 149), 4, 4, paint);

        drawText(canvas, "Press Home, then move to Apps", 80, 215, 37, Color.WHITE, true);
        drawText(canvas, "Menus may say Apps or Your apps", 80, 257, 23,
                Color.rgb(177, 190, 208), false);

        drawTvTile(canvas, 80, 330, 230, 145, "Live TV", Color.rgb(83, 100, 135), false);
        drawTvTile(canvas, 350, 330, 230, 145, "YouTube", Color.rgb(191, 43, 45), false);
        drawTvTile(canvas, 620, 330, 230, 145, "Prime Video", Color.rgb(43, 92, 158), false);
        drawTvTile(canvas, 890, 330, 230, 145, "Apps", Color.rgb(62, 123, 255), true);

        drawRemoteHint(canvas, 826, 175, "1", "Open Apps", "Press OK on the remote");
        canvas.restore();
    }

    private void drawTvApps(Canvas canvas, float w, float h) {
        beginTv(canvas, w, h);
        drawText(canvas, "Apps", 80, 76, 30, Color.WHITE, true);
        drawText(canvas, "Your apps", 80, 145, 40, Color.WHITE, true);
        drawText(canvas, "Find ConfluenceTV in the installed apps row", 80, 188, 23,
                Color.rgb(177, 190, 208), false);

        drawTvTile(canvas, 80, 250, 300, 150, "YouTube", Color.rgb(191, 43, 45), false);
        drawTvTile(canvas, 410, 250, 300, 150, "Netflix", Color.rgb(20, 20, 20), false);
        drawConfluenceTile(canvas, 740, 250, 360, 150, true);
        drawTvTile(canvas, 80, 450, 300, 135, "Prime Video", Color.rgb(43, 92, 158), false);
        drawTvTile(canvas, 410, 450, 300, 135, "Hotstar", Color.rgb(30, 61, 117), false);
        drawTvTile(canvas, 740, 450, 360, 135, "Settings", Color.rgb(80, 91, 108), false);

        drawRemoteHint(canvas, 792, 84, "2", "Select ConfluenceTV", "Press OK to open");
        canvas.restore();
    }

    private void drawTvActivation(Canvas canvas, float w, float h) {
        beginTv(canvas, w, h);
        fill(Color.rgb(15, 57, 91));
        canvas.drawRect(42, 42, 410, 620, paint);
        drawAppIcon(canvas, 145, 100, 160);
        drawText(canvas, "ConfluenceTV", 95, 330, 36, Color.WHITE, true);
        drawText(canvas, "Nielsen Streaming Panel", 95, 372, 21,
                Color.rgb(198, 222, 240), false);
        fill(Color.rgb(23, 103, 178));
        canvas.drawRoundRect(new RectF(88, 455, 355, 555), 18, 18, paint);
        drawText(canvas, "Earn ₹170 after", 120, 494, 24, Color.WHITE, true);
        drawText(canvas, "successful activation", 120, 529, 20, Color.WHITE, false);

        drawText(canvas, "Activate on your TV", 475, 100, 38,
                Color.rgb(22, 45, 70), true);
        drawText(canvas, "Use the same mobile number used during registration.",
                475, 145, 21, Color.rgb(83, 97, 114), false);

        drawText(canvas, "Mobile number", 475, 225, 21, Color.rgb(55, 69, 84), true);
        drawInput(canvas, 475, 245, 650, 70, "+91  98XXXXXX10");
        drawText(canvas, "OTP", 475, 365, 21, Color.rgb(55, 69, 84), true);
        drawInput(canvas, 475, 385, 330, 70, "•  •  •  •  •  •");

        fill(Color.rgb(45, 110, 229));
        canvas.drawRoundRect(new RectF(830, 385, 1090, 455), 14, 14, paint);
        drawText(canvas, "VERIFY", 900, 430, 22, Color.WHITE, true);

        fill(Color.rgb(224, 248, 236));
        canvas.drawRoundRect(new RectF(475, 500, 1090, 575), 16, 16, paint);
        drawText(canvas, "3  Keep the app open until activation is confirmed",
                510, 545, 21, Color.rgb(24, 111, 71), true);
        canvas.restore();
    }

    private void beginTv(Canvas canvas, float w, float h) {
        float s = Math.min(w / 1200f, h / 690f);
        float ox = (w - 1200f * s) / 2f;
        float oy = (h - 690f * s) / 2f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(s, s);

        paint.setShadowLayer(18, 0, 10, Color.argb(70, 0, 0, 0));
        fill(Color.rgb(15, 20, 28));
        canvas.drawRoundRect(new RectF(28, 18, 1172, 652), 28, 28, paint);
        paint.clearShadowLayer();

        fill(Color.rgb(17, 27, 42));
        canvas.drawRoundRect(new RectF(42, 32, 1158, 624), 20, 20, paint);
        fill(Color.rgb(92, 100, 111));
        canvas.drawRoundRect(new RectF(530, 655, 670, 671), 8, 8, paint);
    }

    private void drawTvTile(
            Canvas canvas,
            float x,
            float y,
            float width,
            float height,
            String label,
            int color,
            boolean selected) {
        if (selected) {
            stroke(Color.rgb(255, 212, 73), 8);
            canvas.drawRoundRect(new RectF(x - 10, y - 10, x + width + 10, y + height + 10),
                    28, 28, paint);
        }
        fill(color);
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), 22, 22, paint);
        drawText(canvas, label, x + 24, y + height - 26, 27, Color.WHITE, true);
    }

    private void drawConfluenceTile(
            Canvas canvas,
            float x,
            float y,
            float width,
            float height,
            boolean selected) {
        if (selected) {
            stroke(Color.rgb(255, 212, 73), 8);
            canvas.drawRoundRect(new RectF(x - 10, y - 10, x + width + 10, y + height + 10),
                    28, 28, paint);
        }
        fill(Color.rgb(25, 124, 183));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), 22, 22, paint);
        drawAppIcon(canvas, x + 20, y + 25, 96);
        drawText(canvas, "ConfluenceTV", x + 135, y + 72, 27, Color.WHITE, true);
        drawText(canvas, "Nielsen", x + 135, y + 108, 20,
                Color.rgb(219, 238, 249), false);
    }

    private void drawRemoteHint(
            Canvas canvas,
            float x,
            float y,
            String number,
            String title,
            String body) {
        fill(Color.rgb(247, 249, 252));
        canvas.drawRoundRect(new RectF(x, y, x + 320, y + 105), 18, 18, paint);
        drawText(canvas, number, x + 25, y + 45, 34, Color.rgb(62, 123, 255), true);
        drawText(canvas, title, x + 75, y + 43, 25, Color.rgb(31, 44, 61), true);
        drawText(canvas, body, x + 25, y + 78, 19, Color.rgb(87, 101, 119), false);
    }

    private void drawInput(
            Canvas canvas,
            float x,
            float y,
            float width,
            float height,
            String text) {
        fill(Color.WHITE);
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), 14, 14, paint);
        stroke(Color.rgb(177, 189, 203), 2);
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), 14, 14, paint);
        drawText(canvas, text, x + 26, y + 44, 22, Color.rgb(48, 59, 72), false);
    }

    private void drawAppIcon(Canvas canvas, float x, float y, float size) {
        fill(Color.rgb(12, 155, 211));
        canvas.drawRoundRect(new RectF(x, y, x + size, y + size), size * 0.22f,
                size * 0.22f, paint);

        stroke(Color.WHITE, Math.max(3f, size * 0.035f));
        float cx = x + size / 2f;
        float cy = y + size / 2f;
        float arm = size * 0.28f;
        float gap = size * 0.12f;
        canvas.drawLine(cx - arm, cy - arm, cx - gap, cy - gap, paint);
        canvas.drawLine(cx + arm, cy - arm, cx + gap, cy - gap, paint);
        canvas.drawLine(cx - arm, cy + arm, cx - gap, cy + gap, paint);
        canvas.drawLine(cx + arm, cy + arm, cx + gap, cy + gap, paint);
        canvas.drawLine(cx, cy - arm, cx, cy - gap, paint);
        canvas.drawLine(cx, cy + arm, cx, cy + gap, paint);
        canvas.drawLine(cx - arm, cy, cx - gap, cy, paint);
        canvas.drawLine(cx + arm, cy, cx + gap, cy, paint);
    }

    private void drawText(
            Canvas canvas,
            String text,
            float x,
            float y,
            float size,
            int color,
            boolean bold) {
        fill(color);
        paint.setTextSize(size);
        paint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        canvas.drawText(text, x, y, paint);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setStrokeWidth(1f);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
