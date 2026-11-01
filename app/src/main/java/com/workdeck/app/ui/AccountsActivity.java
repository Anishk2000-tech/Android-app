package com.workdeck.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.notify.Notifier;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.TabManager;
import com.workdeck.app.web.WebViewSupport;

/**
 * Account manager. Without an account id it lists all accounts; with one it shows
 * that account's own settings (name, colour, Teams/Outlook, view, notifications,
 * sign-out and delete).
 */
public final class AccountsActivity extends Activity {
    public static final String EXTRA_ACCOUNT = "account";

    private static final int[] COLOR_NAMES = {
            R.string.color_teal, R.string.color_purple, R.string.color_orange, R.string.color_blue,
            R.string.color_pink, R.string.color_green, R.string.color_amber, R.string.color_slate,
    };

    private Accounts mAccounts;
    private TabManager mTabs;
    private Account mAccount;
    private boolean mBuilt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAccounts = Accounts.get(this);
        mTabs = TabManager.get(this);
        String id = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (id != null) {
            mAccount = mAccounts.find(id);
            if (mAccount == null) {
                finish();
                return;
            }
        }
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The list shows names and states that the detail page can change.
        if (mBuilt && mAccount == null) build();
        mBuilt = true;
    }

    private void build() {
        if (mAccount == null) buildList();
        else buildDetail(mAccount);
    }

    // ------------------------------------------------------------------ list

    private String appsSummary(Account a) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(a.isPersonal() ? R.string.acc_type_personal_short : R.string.acc_type_work_short));
        for (Site s : Site.values()) {
            if (!a.siteEnabled(s)) continue;
            sb.append(" · ").append(getString(s.titleRes));
            BrowserTab t = mTabs.existing(a.key(s));
            int n = t == null ? 0 : t.unreadCount();
            if (n > 0) sb.append(" (").append(n).append(')');
        }
        if (!a.background) sb.append(" · ").append(getString(R.string.acc_no_background_short));
        return sb.toString();
    }

    private void buildList() {
        LinearLayout c = Ui.page(this, getString(R.string.acc_title), true);
        Ui.para(c, getString(R.string.acc_intro));
        if (!WebViewSupport.supportsProfiles()) needsUpdateCard(c);

        LinearLayout card = Ui.card(c);
        for (final Account a : mAccounts.list()) {
            TextView sum = Ui.clickRow(card, a.name, appsSummary(a), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AccountsActivity.this, AccountsActivity.class)
                            .putExtra(EXTRA_ACCOUNT, a.id));
                }
            });
            // Colour marker in front of the name.
            TextView title = (TextView) ((LinearLayout) sum.getParent()).getChildAt(0);
            title.setText("● " + a.name);
            title.setTextColor(AccountDialogs.accent(this, a));
        }
        Ui.clickRow(card, "+  " + getString(R.string.acc_add), getString(R.string.acc_add_desc),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AccountDialogs.showAdd(AccountsActivity.this, new AccountDialogs.Added() {
                            @Override
                            public void onAdded(Account a) {
                                Toast.makeText(AccountsActivity.this, getString(R.string.acc_added, a.name),
                                        Toast.LENGTH_LONG).show();
                                buildList();
                            }
                        });
                    }
                });
        Ui.heading(c, getString(R.string.acc_how_title));
        Ui.para(c, getString(R.string.acc_how_text));
    }

    private void needsUpdateCard(LinearLayout c) {
        LinearLayout warn = Ui.card(c);
        Ui.clickRow(warn, getString(R.string.acc_needs_update_title), getString(R.string.acc_needs_update_text),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AccountDialogs.openStore(AccountsActivity.this, "com.google.android.webview");
                    }
                });
    }

    // ------------------------------------------------------------------ one account

    private interface Toggle {
        void set(boolean v);
    }

    private Switch toggle(LinearLayout parent, String title, String summary, boolean value, final Toggle t) {
        return Ui.switchRow(parent, title, summary, value, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                t.set(checked);
            }
        });
    }

    private void save() {
        mAccounts.save();
    }

    private String viewLabel(Account a, Site s) {
        return getString(a.isDesktop(s) ? R.string.view_desktop_now : R.string.view_mobile_now);
    }

    private void buildDetail(final Account a) {
        LinearLayout c = Ui.page(this, a.name, true);
        if (!a.usesDefaultProfile() && !WebViewSupport.supportsProfiles()) needsUpdateCard(c);

        // ---- name & look
        Ui.heading(c, getString(R.string.acc_sec_account));
        LinearLayout acc = Ui.card(c);
        final TextView[] nameSum = new TextView[1];
        nameSum[0] = Ui.clickRow(acc, getString(R.string.acc_rename), a.name, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rename(a);
            }
        });
        final TextView[] colorSum = new TextView[1];
        colorSum[0] = Ui.clickRow(acc, getString(R.string.acc_color), colorName(a), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseColor(a, colorSum[0]);
            }
        });
        colorSum[0].setTextColor(AccountDialogs.accent(this, a));
        final TextView[] typeSum = new TextView[1];
        typeSum[0] = Ui.clickRow(acc, getString(R.string.set_account_type), typeLabel(a), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseType(a, typeSum[0]);
            }
        });

        // ---- apps
        Ui.heading(c, getString(R.string.acc_sec_apps));
        LinearLayout apps = Ui.card(c);
        final Switch[] enabled = new Switch[2];
        for (final Site s : Site.values()) {
            final int idx = s.ordinal();
            enabled[idx] = toggle(apps, getString(s == Site.TEAMS ? R.string.set_enable_teams
                    : R.string.set_enable_outlook), null, a.siteEnabled(s), new Toggle() {
                @Override
                public void set(boolean v) {
                    setSiteEnabled(a, s, v, enabled[idx]);
                }
            });
        }
        for (final Site s : Site.values()) {
            final TextView[] sum = new TextView[1];
            sum[0] = Ui.clickRow(apps, getString(s == Site.TEAMS ? R.string.set_view_teams : R.string.set_view_outlook),
                    viewLabel(a, s), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean desktop = !a.isDesktop(s);
                            BrowserTab t = mTabs.existing(a.key(s));
                            if (t != null && t.isCreated()) {
                                t.setDesktop(desktop);
                            } else {
                                a.setDesktop(s, desktop);
                                save();
                            }
                            sum[0].setText(viewLabel(a, s));
                        }
                    });
        }
        Ui.clickRow(apps, getString(R.string.set_custom_urls), getString(R.string.set_custom_urls_desc),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        editCustomUrls(a);
                    }
                });

        // ---- notifications & background
        Ui.heading(c, getString(R.string.acc_sec_notifications));
        LinearLayout notif = Ui.card(c);
        toggle(notif, getString(R.string.acc_background), getString(R.string.acc_background_desc), a.background,
                new Toggle() {
                    @Override
                    public void set(boolean v) {
                        a.background = v;
                        save();
                        if (v) {
                            mTabs.startBackground();
                        } else if (a != mAccounts.active()) {
                            // Free the memory now; it starts again when opened.
                            mTabs.destroyTabsOf(a);
                        }
                    }
                });
        toggle(notif, getString(R.string.set_notify_teams), null, a.notificationsEnabled(Site.TEAMS), new Toggle() {
            @Override
            public void set(boolean v) {
                a.setNotificationsEnabled(Site.TEAMS, v);
                save();
                if (!v) Notifier.get(AccountsActivity.this).clearTab(a.key(Site.TEAMS));
            }
        });
        toggle(notif, getString(R.string.set_notify_outlook), null, a.notificationsEnabled(Site.OUTLOOK),
                new Toggle() {
                    @Override
                    public void set(boolean v) {
                        a.setNotificationsEnabled(Site.OUTLOOK, v);
                        save();
                        if (!v) Notifier.get(AccountsActivity.this).clearTab(a.key(Site.OUTLOOK));
                    }
                });

        // ---- order
        if (mAccounts.multiple()) {
            Ui.heading(c, getString(R.string.acc_sec_order));
            LinearLayout order = Ui.card(c);
            Ui.clickRow(order, getString(R.string.acc_move_left), getString(R.string.acc_move_desc),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mAccounts.move(a, -1);
                            Toast.makeText(AccountsActivity.this, R.string.acc_moved, Toast.LENGTH_SHORT).show();
                        }
                    });
            Ui.clickRow(order, getString(R.string.acc_move_right), getString(R.string.acc_move_desc),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mAccounts.move(a, 1);
                            Toast.makeText(AccountsActivity.this, R.string.acc_moved, Toast.LENGTH_SHORT).show();
                        }
                    });
        }

        // ---- sign-in
        Ui.heading(c, getString(R.string.acc_sec_signin));
        LinearLayout data = Ui.card(c);
        Ui.clickRow(data, getString(R.string.acc_sign_out), getString(R.string.acc_sign_out_desc, a.name),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(AccountsActivity.this)
                                .setMessage(getString(R.string.acc_sign_out_confirm, a.name))
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.acc_sign_out, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        mTabs.clearAccountData(a, new Runnable() {
                                            @Override
                                            public void run() {
                                                Toast.makeText(AccountsActivity.this,
                                                        getString(R.string.acc_signed_out, a.name),
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }
                                }).show();
                    }
                });
        if (mAccounts.multiple()) {
            Ui.clickRow(data, getString(R.string.acc_delete), getString(R.string.acc_delete_desc),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDelete(a);
                        }
                    });
        }
        Ui.para(c, getString(R.string.acc_storage_note));
    }

    private String colorName(Account a) {
        return getString(COLOR_NAMES[Math.abs(a.color) % COLOR_NAMES.length]);
    }

    private String typeLabel(Account a) {
        return getString(a.isPersonal() ? R.string.setup_account_personal : R.string.setup_account_work);
    }

    private void rename(final Account a) {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        name.setText(a.name);
        name.setSelection(a.name.length());
        LinearLayout box = new LinearLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(R.string.acc_rename).setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        a.name = n;
                        save();
                        buildDetail(a);
                    }
                }).show();
    }

    private void chooseColor(final Account a, final TextView sum) {
        String[] labels = new String[COLOR_NAMES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(COLOR_NAMES[i]);
        new AlertDialog.Builder(this).setTitle(R.string.acc_color)
                .setSingleChoiceItems(labels, Math.abs(a.color) % COLOR_NAMES.length,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                a.color = which;
                                save();
                                sum.setText(colorName(a));
                                sum.setTextColor(AccountDialogs.accent(AccountsActivity.this, a));
                                d.dismiss();
                            }
                        }).show();
    }

    private void chooseType(final Account a, final TextView sum) {
        final String[] values = {Prefs.ACCOUNT_WORK, Prefs.ACCOUNT_PERSONAL};
        String[] labels = {getString(R.string.setup_account_work), getString(R.string.setup_account_personal)};
        new AlertDialog.Builder(this).setTitle(R.string.set_account_type)
                .setSingleChoiceItems(labels, a.isPersonal() ? 1 : 0, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!values[which].equals(a.type)) {
                            a.type = values[which];
                            save();
                            for (BrowserTab t : mTabs.tabsOf(a)) {
                                if (t.isCreated()) t.loadHome();
                            }
                        }
                        sum.setText(typeLabel(a));
                        d.dismiss();
                    }
                }).show();
    }

    private void setSiteEnabled(Account a, Site s, boolean v, Switch sw) {
        Site other = s == Site.TEAMS ? Site.OUTLOOK : Site.TEAMS;
        if (!v && !a.siteEnabled(other)) {
            sw.setChecked(true);
            Toast.makeText(this, R.string.setup_need_one_app, Toast.LENGTH_SHORT).show();
            return;
        }
        a.setSiteEnabled(s, v);
        save();
        if (!v) {
            BrowserTab t = mTabs.existing(a.key(s));
            if (t != null) mTabs.destroyTab(t);
            Notifier.get(this).clearTab(a.key(s));
        }
    }

    private void editCustomUrls(final Account a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final EditText teams = new EditText(this);
        teams.setHint(getString(R.string.site_teams) + ": " + getString(R.string.set_custom_url_hint));
        teams.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        teams.setText(a.customUrl(Site.TEAMS));
        final EditText outlook = new EditText(this);
        outlook.setHint(getString(R.string.site_outlook) + ": " + getString(R.string.set_custom_url_hint));
        outlook.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        outlook.setText(a.customUrl(Site.OUTLOOK));
        box.addView(teams);
        box.addView(outlook);
        new AlertDialog.Builder(this).setTitle(R.string.set_custom_urls).setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        a.setCustomUrl(Site.TEAMS, teams.getText().toString());
                        a.setCustomUrl(Site.OUTLOOK, outlook.getText().toString());
                        save();
                        for (BrowserTab t : mTabs.tabsOf(a)) {
                            if (t.isCreated()) t.loadHome();
                        }
                    }
                }).show();
    }

    private void confirmDelete(final Account a) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.acc_delete_title, a.name))
                .setMessage(R.string.acc_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.acc_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Notifier n = Notifier.get(AccountsActivity.this);
                        for (Site s : Site.values()) n.clearTab(a.key(s));
                        mTabs.deleteAccount(a);
                        Toast.makeText(AccountsActivity.this, getString(R.string.acc_deleted, a.name),
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }).show();
    }
}
