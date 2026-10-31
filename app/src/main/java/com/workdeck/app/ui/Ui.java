package com.workdeck.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.workdeck.app.R;

/** Tiny helpers for building the simple Setup/Settings screens in code. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int color(Context c, int res) {
        return c.getColor(res);
    }

    /** Creates a scrolling page with a title bar and returns the content column. */
    static LinearLayout page(final Activity a, CharSequence title, boolean back) {
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(a, R.color.wd_bg));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, back ? 4 : 20), 0, dp(a, 16), 0);
        if (back) {
            ImageButton b = new ImageButton(a, null, android.R.attr.borderlessButtonStyle);
            b.setImageResource(R.drawable.ic_close);
            b.setColorFilter(color(a, R.color.wd_icon));
            b.setContentDescription(a.getString(R.string.desc_close));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.finish();
                }
            });
            bar.addView(b, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        }
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(a, R.color.wd_text));
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 60)));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 32));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        a.setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
            boolean night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : light, light);
            }
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                            | WindowInsets.Type.displayCutout());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
        }
        return content;
    }

    static TextView heading(LinearLayout parent, CharSequence text) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(c, R.color.wd_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 22);
        lp.bottomMargin = dp(c, 6);
        parent.addView(t, lp);
        return t;
    }

    static TextView para(LinearLayout parent, CharSequence text) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setLineSpacing(0, 1.15f);
        t.setTextColor(color(c, R.color.wd_text2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        parent.addView(t, lp);
        return t;
    }

    static LinearLayout card(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        parent.addView(card, lp);
        return card;
    }

    private static LinearLayout textColumn(Context c, CharSequence title, CharSequence summary, TextView[] outSummary) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(color(c, R.color.wd_text));
        col.addView(t);
        TextView s = new TextView(c);
        s.setTextSize(13);
        s.setTextColor(color(c, R.color.wd_text2));
        if (TextUtils.isEmpty(summary)) {
            s.setVisibility(View.GONE);
        } else {
            s.setText(summary);
        }
        col.addView(s);
        if (outSummary != null) outSummary[0] = s;
        return col;
    }

    private static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));
        return row;
    }

    static Switch switchRow(LinearLayout parent, CharSequence title, CharSequence summary, boolean checked,
            CompoundButton.OnCheckedChangeListener l) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        row.addView(textColumn(c, title, summary, null),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        parent.addView(row);
        return sw;
    }

    /** A tappable row; returns its summary TextView so callers can update it. */
    static TextView clickRow(LinearLayout parent, CharSequence title, CharSequence summary, View.OnClickListener l) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        TextView[] out = new TextView[1];
        row.addView(textColumn(c, title, summary, out),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(l);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        parent.addView(row);
        out[0].setVisibility(View.VISIBLE);
        return out[0];
    }

    /** Permission row: title, description, status line and an action button. */
    static final class PermRow {
        final TextView status;
        final Button button;

        PermRow(TextView status, Button button) {
            this.status = status;
            this.button = button;
        }

        void set(boolean ok, boolean optional) {
            Context c = status.getContext();
            if (ok) {
                status.setText(R.string.status_allowed);
                status.setTextColor(color(c, R.color.wd_ok));
                button.setVisibility(View.GONE);
            } else {
                status.setText(optional ? R.string.status_optional : R.string.status_needed);
                status.setTextColor(color(c, optional ? R.color.wd_text2 : R.color.wd_warn));
                button.setVisibility(View.VISIBLE);
            }
        }
    }

    static PermRow permRow(LinearLayout parent, CharSequence title, CharSequence desc, CharSequence buttonText,
            View.OnClickListener l) {
        Context c = parent.getContext();
        LinearLayout row = row(c);
        LinearLayout col = textColumn(c, title, desc, null);
        TextView status = new TextView(c);
        status.setTextSize(13);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(status);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(buttonText);
        b.setTextColor(color(c, R.color.wd_primary));
        b.setOnClickListener(l);
        row.addView(b);
        parent.addView(row);
        return new PermRow(status, b);
    }

    static Button primaryButton(LinearLayout parent, CharSequence text, View.OnClickListener l) {
        Context c = parent.getContext();
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(color(c, R.color.wd_on_primary));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(c, R.color.wd_primary)));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 56));
        lp.topMargin = dp(c, 14);
        parent.addView(b, lp);
        return b;
    }
}
