package com.workdeck.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.web.WebViewSupport;

/** Dialogs shared by the main screen and the account manager. */
final class AccountDialogs {
    private AccountDialogs() {}

    interface Added {
        void onAdded(Account account);
    }

    /** Asks for a name and account type, then creates the account. */
    static void showAdd(final Activity a, final Added done) {
        if (!WebViewSupport.supportsProfiles()) {
            showNeedsUpdate(a);
            return;
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, Ui.dp(a, 8), pad, 0);
        final EditText name = new EditText(a);
        name.setHint(R.string.acc_name_hint);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(name);
        RadioGroup group = new RadioGroup(a);
        final RadioButton work = new RadioButton(a);
        work.setText(R.string.setup_account_work);
        work.setId(View.generateViewId());
        RadioButton personal = new RadioButton(a);
        personal.setText(R.string.setup_account_personal);
        personal.setId(View.generateViewId());
        group.addView(work);
        group.addView(personal);
        group.check(work.getId());
        box.addView(group);
        final RadioGroup g = group;
        new AlertDialog.Builder(a)
                .setTitle(R.string.acc_add_title)
                .setMessage(R.string.acc_add_text)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.acc_add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String type = g.getCheckedRadioButtonId() == work.getId() ? Prefs.ACCOUNT_WORK
                                : Prefs.ACCOUNT_PERSONAL;
                        Account acc = Accounts.get(a).add(name.getText().toString(), type);
                        if (done != null) done.onAdded(acc);
                    }
                }).show();
    }

    /** Separate sign-ins need a newer Android System WebView. */
    static void showNeedsUpdate(final Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.acc_needs_update_title)
                .setMessage(R.string.acc_needs_update_text)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.outdated_update, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openStore(a, "com.google.android.webview");
                    }
                }).show();
    }

    static void openStore(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The account colour, lightened on dark backgrounds so text stays readable. */
    static int accent(Context c, Account a) {
        int col = a.colorValue();
        if (!isNight(c)) return col;
        int r = Color.red(col), g = Color.green(col), b = Color.blue(col);
        return Color.rgb(r + (255 - r) * 45 / 100, g + (255 - g) * 45 / 100, b + (255 - b) * 45 / 100);
    }
}
