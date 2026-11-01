package com.workdeck.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.WebView;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.service.KeepAliveService;
import com.workdeck.app.web.TabManager;

/** All options, grouped simply. Changes apply immediately. */
public final class SettingsActivity extends Activity {
    private Prefs mPrefs;
    private TextView mAccountsRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = Prefs.get(this);
        build();
    }

    private interface Toggle {
        void set(boolean v);
    }

    private Switch toggle(LinearLayout parent, int title, int summary, boolean value, final Toggle t) {
        return Ui.switchRow(parent, getString(title), summary == 0 ? null : getString(summary), value,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        t.set(checked);
                    }
                });
    }

    private String routeLabel() {
        return getString(Prefs.ROUTE_EARPIECE.equals(mPrefs.defaultRoute()) ? R.string.route_earpiece
                : R.string.route_speaker);
    }

    private void build() {
        LinearLayout c = Ui.page(this, getString(R.string.settings_title), true);

        // ---- accounts
        Ui.heading(c, getString(R.string.sec_accounts));
        LinearLayout acc = Ui.card(c);
        mAccountsRow = Ui.clickRow(acc, getString(R.string.set_accounts), accountsSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, AccountsActivity.class));
                    }
                });

        // ---- view
        Ui.heading(c, getString(R.string.sec_view));
        LinearLayout view = Ui.card(c);
        final TextView[] widthSum = new TextView[1];
        widthSum[0] = Ui.clickRow(view, getString(R.string.set_desktop_width),
                getString(R.string.desktop_width_value, mPrefs.desktopWidth()) + " · "
                        + getString(R.string.set_desktop_width_desc), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final int[] widths = Prefs.DESKTOP_WIDTHS;
                        String[] labels = new String[widths.length];
                        int checked = 0;
                        for (int i = 0; i < widths.length; i++) {
                            labels[i] = widths[i] + " px";
                            if (widths[i] == mPrefs.desktopWidth()) checked = i;
                        }
                        new AlertDialog.Builder(SettingsActivity.this).setTitle(R.string.set_desktop_width)
                                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        mPrefs.setDesktopWidth(widths[which]);
                                        TabManager.get(SettingsActivity.this).refreshLayoutWidth();
                                        widthSum[0].setText(getString(R.string.desktop_width_value, widths[which])
                                                + " · " + getString(R.string.set_desktop_width_desc));
                                        d.dismiss();
                                    }
                                }).show();
                    }
                });

        // ---- background & notifications
        Ui.heading(c, getString(R.string.sec_background));
        LinearLayout bg = Ui.card(c);
        toggle(bg, R.string.set_keep_alive, R.string.set_keep_alive_desc, mPrefs.keepAlive(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setKeepAlive(v);
                if (v) KeepAliveService.start(SettingsActivity.this);
                else KeepAliveService.stop(SettingsActivity.this);
            }
        });
        toggle(bg, R.string.set_wakelock, R.string.set_wakelock_desc, mPrefs.cpuWakeLock(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setCpuWakeLock(v);
                KeepAliveService.onSettingsChanged();
            }
        });
        toggle(bg, R.string.set_boot, 0, mPrefs.startOnBoot(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setStartOnBoot(v);
            }
        });
        toggle(bg, R.string.set_auto_refresh, R.string.set_auto_refresh_desc, mPrefs.autoRefresh(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setAutoRefresh(v);
            }
        });
        Ui.clickRow(bg, getString(R.string.set_channels), getString(R.string.set_channels_desc),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // ---- calls
        Ui.heading(c, getString(R.string.sec_calls));
        LinearLayout calls = Ui.card(c);
        toggle(calls, R.string.set_call_alerts, R.string.set_call_alerts_desc, mPrefs.callAlerts(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setCallAlerts(v);
            }
        });
        toggle(calls, R.string.set_call_ringtone, 0, mPrefs.callRingtone(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setCallRingtone(v);
            }
        });
        final TextView[] routeSum = new TextView[1];
        routeSum[0] = Ui.clickRow(calls, getString(R.string.set_default_route),
                routeLabel() + " · " + getString(R.string.set_default_route_desc), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mPrefs.setDefaultRoute(Prefs.ROUTE_EARPIECE.equals(mPrefs.defaultRoute())
                                ? Prefs.ROUTE_SPEAKER : Prefs.ROUTE_EARPIECE);
                        routeSum[0].setText(routeLabel() + " · " + getString(R.string.set_default_route_desc));
                    }
                });
        toggle(calls, R.string.set_pip, R.string.set_pip_desc, mPrefs.pipOnCall(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setPipOnCall(v);
            }
        });

        // ---- data
        Ui.heading(c, getString(R.string.sec_data));
        LinearLayout data = Ui.card(c);
        Ui.clickRow(data, getString(R.string.set_sign_out), getString(R.string.set_sign_out_desc),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setMessage(R.string.set_sign_out_confirm)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.set_sign_out, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        TabManager.get(SettingsActivity.this).clearAllData(new Runnable() {
                                            @Override
                                            public void run() {
                                                Toast.makeText(SettingsActivity.this, R.string.set_signed_out,
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }
                                }).show();
                    }
                });

        // ---- advanced
        Ui.heading(c, getString(R.string.sec_advanced));
        LinearLayout adv = Ui.card(c);
        toggle(adv, R.string.set_sw_shim, R.string.set_sw_shim_desc, mPrefs.swShim(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setSwShim(v);
                Toast.makeText(SettingsActivity.this, R.string.restart_needed, Toast.LENGTH_LONG).show();
            }
        });
        toggle(adv, R.string.set_debug, R.string.set_debug_desc, mPrefs.webDebugging(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setWebDebugging(v);
                if (v) WebView.setWebContentsDebuggingEnabled(true);
                Toast.makeText(SettingsActivity.this, R.string.restart_needed, Toast.LENGTH_LONG).show();
            }
        });

        // ---- about
        Ui.heading(c, getString(R.string.sec_about));
        String version = "?";
        String webview = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 26) {
            PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) webview = wv.versionName;
        }
        Ui.para(c, getString(R.string.set_version, version, webview));
        Ui.para(c, getString(R.string.set_about_desc));
    }

    private String accountsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Account a : Accounts.get(this).list()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.name);
        }
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAccountsRow != null) mAccountsRow.setText(accountsSummary());
    }
}
