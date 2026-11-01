package com.workdeck.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.workdeck.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu that opens upwards from an account in the bottom bar: its Teams and
 * Outlook, the links opened in it (each with a × to close), "New tab" and settings.
 */
final class DropUp {
    private DropUp() {}

    static final class Item {
        Drawable icon;
        CharSequence title;
        boolean checked;
        int checkColor;
        Runnable onClick;
        /** Non-null: the row gets a × button that runs this. */
        Runnable onClose;
        CharSequence closeDescription;
        /** A thin line above this row. */
        boolean dividerBefore;
    }

    static PopupWindow show(final Activity a, View anchor, List<Item> items) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 6);
        list.setPadding(0, pad, 0, pad);
        for (final Item it : items) {
            if (it.dividerBefore) {
                View line = new View(a);
                line.setBackgroundColor(a.getColor(R.color.wd_divider));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 1));
                lp.topMargin = Ui.dp(a, 4);
                lp.bottomMargin = Ui.dp(a, 4);
                list.addView(line, lp);
            }
            list.addView(row(a, it, holder));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.wd_bg));
        bg.setCornerRadius(Ui.dp(a, 14));
        bg.setStroke(Ui.dp(a, 1), a.getColor(R.color.wd_divider));
        scroll.setBackground(bg);
        scroll.setClipToOutline(true);

        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int margin = Ui.dp(a, 8);
        int width = Math.min(dm.widthPixels - 2 * margin, Ui.dp(a, 320));
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((int) (dm.heightPixels * 0.7f), View.MeasureSpec.AT_MOST));
        int height = scroll.getMeasuredHeight();

        PopupWindow pw = new PopupWindow(scroll, width, height, true);
        holder[0] = pw;
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(Ui.dp(a, 12));
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int x = Math.max(margin, Math.min(loc[0], dm.widthPixels - width - margin));
        int y = Math.max(margin, loc[1] - height - Ui.dp(a, 6));
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        return pw;
    }

    private static View row(final Activity a, final Item it, final PopupWindow[] holder) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(a, 48));
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, it.onClose != null ? 4 : 16), 0);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) holder[0].dismiss();
                if (it.onClick != null) it.onClick.run();
            }
        });
        if (it.icon != null) {
            ImageView icon = new ImageView(a);
            icon.setImageDrawable(it.icon);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
        }
        TextView title = new TextView(a);
        title.setText(it.title);
        title.setTextSize(16);
        title.setTextColor(a.getColor(R.color.wd_text));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (it.checked) title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = Ui.dp(a, it.icon != null ? 14 : 0);
        row.addView(title, tlp);
        if (it.checked) {
            TextView check = new TextView(a);
            check.setText("✓");
            check.setTextSize(17);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setTextColor(it.checkColor);
            check.setPadding(Ui.dp(a, 8), 0, it.onClose != null ? Ui.dp(a, 4) : 0, 0);
            row.addView(check);
        }
        if (it.onClose != null) {
            ImageButton close = new ImageButton(a, null, android.R.attr.borderlessButtonStyle);
            close.setImageResource(R.drawable.ic_close);
            close.setColorFilter(a.getColor(R.color.wd_icon));
            close.setContentDescription(it.closeDescription);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    it.onClose.run();
                }
            });
            row.addView(close, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        }
        return row;
    }

    static List<Item> list() {
        return new ArrayList<>();
    }
}
