package com.nlsn.tvcompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** Full-screen, swipeable visual guide for the Google Play and TV activation flow. */
public final class TutorialActivity extends AppCompatActivity {

    private static final String PREFS = "assistant_preferences";
    private static final String PREF_LANGUAGE = "language";
    private static final int STEP_COUNT = 6;

    private static final int COLOR_NAVY = Color.rgb(15, 42, 68);
    private static final int COLOR_BLUE = Color.rgb(32, 103, 227);
    private static final int COLOR_TEXT = Color.rgb(31, 41, 55);
    private static final int COLOR_MUTED = Color.rgb(102, 112, 133);
    private static final int COLOR_BORDER = Color.rgb(208, 213, 221);
    private static final int COLOR_BACKGROUND = Color.rgb(246, 248, 252);

    private TutorialPreviewView preview;
    private TextView stepLabel;
    private TextView title;
    private TextView body;
    private TextView dots;
    private Button previous;
    private Button next;
    private int step;
    private float touchDownX;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences preferences =
                newBase.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String language = preferences.getString(PREF_LANGUAGE, "en");
        Locale locale = Locale.forLanguageTag(language);
        Locale.setDefault(locale);

        Configuration configuration =
                new Configuration(newBase.getResources().getConfiguration());
        configuration.setLocale(locale);
        super.attachBaseContext(newBase.createConfigurationContext(configuration));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        buildUi();
        showStep(0);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("‹");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        close.setTextColor(COLOR_BLUE);
        close.setBackground(rounded(Color.WHITE, 14, COLOR_BORDER));
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView heading = text(getString(R.string.visual_guide_title), 22, COLOR_TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(14), 0, 0, 0);
        top.addView(heading, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1));
        root.addView(top);

        stepLabel = text("", 13, COLOR_BLUE);
        stepLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(stepLabel, spaced(0, 18, 0, 8));

        preview = new TutorialPreviewView(this);
        preview.setBackground(rounded(Color.WHITE, 20, COLOR_BORDER));
        preview.setContentDescription(getString(R.string.preview_heading));
        preview.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float delta = event.getX() - touchDownX;
                if (Math.abs(delta) > dp(52)) {
                    showStep(step + (delta < 0 ? 1 : -1));
                }
                return true;
            }
            return true;
        });
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        title = text("", 21, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, spaced(2, 16, 2, 0));

        body = text("", 15, COLOR_MUTED);
        body.setLineSpacing(0, 1.15f);
        root.addView(body, spaced(2, 7, 2, 0));

        dots = text("", 16, COLOR_BLUE);
        dots.setGravity(Gravity.CENTER);
        root.addView(dots, spaced(0, 14, 0, 10));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        previous = new Button(this);
        previous.setAllCaps(false);
        previous.setText(R.string.previous);
        previous.setTextColor(COLOR_BLUE);
        previous.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        previous.setBackground(rounded(Color.WHITE, 15, COLOR_BORDER));
        previous.setOnClickListener(v -> showStep(step - 1));
        nav.addView(previous, new LinearLayout.LayoutParams(0, dp(54), 1));

        next = new Button(this);
        next.setAllCaps(false);
        next.setTextColor(Color.WHITE);
        next.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        next.setBackground(rounded(COLOR_BLUE, 15, COLOR_BLUE));
        next.setOnClickListener(v -> {
            if (step == STEP_COUNT - 1) {
                finish();
            } else {
                showStep(step + 1);
            }
        });
        LinearLayout.LayoutParams nextParams =
                new LinearLayout.LayoutParams(0, dp(54), 1);
        nextParams.setMargins(dp(10), 0, 0, 0);
        nav.addView(next, nextParams);
        root.addView(nav);

        TextView note = text(getString(R.string.visual_guide_note), 12, COLOR_MUTED);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(0, 1.12f);
        root.addView(note, spaced(10, 16, 10, 0));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void showStep(int value) {
        int bounded = Math.max(0, Math.min(STEP_COUNT - 1, value));
        step = bounded;
        preview.setStep(step);

        int[] titleIds = {
                R.string.preview_title_1,
                R.string.preview_title_2,
                R.string.preview_title_3,
                R.string.preview_title_4,
                R.string.preview_title_5,
                R.string.preview_title_6
        };
        int[] bodyIds = {
                R.string.preview_body_1,
                R.string.preview_body_2,
                R.string.preview_body_3,
                R.string.preview_body_4,
                R.string.preview_body_5,
                R.string.preview_body_6
        };

        stepLabel.setText(getString(R.string.preview_step, step + 1, STEP_COUNT));
        title.setText(titleIds[step]);
        body.setText(bodyIds[step]);
        dots.setText(dots(step));
        previous.setEnabled(step > 0);
        previous.setAlpha(step > 0 ? 1f : 0.45f);
        next.setText(step == STEP_COUNT - 1 ? R.string.done : R.string.next);
    }

    private String dots(int active) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < STEP_COUNT; i++) {
            if (i > 0) {
                value.append("  ");
            }
            value.append(i == active ? "●" : "○");
        }
        return value.toString();
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams spaced(
            int left,
            int top,
            int right,
            int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
