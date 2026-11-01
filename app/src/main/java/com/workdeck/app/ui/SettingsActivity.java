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
import com.workdeck.app.Site;
import com.workdeck.app.notify.SoundPlayer;
import com.workdeck.app.service.KeepAliveService;
import com.workdeck.app.web.TabManager;

/** All options, grouped simply. Changes apply immediately. */
public final class SettingsActivity extends Activity {
    private Prefs mPrefs;
    private TextView mAccountsRow;
    private TextView mReliabilityRow;
    private static final int REQ_RINGTONE = 10;
    private static final int REQ_SOUND_FILE = 11;
    private Site mSoundSite;
    private final java.util.Map<Site, TextView> mSoundRows = new java.util.EnumMap<>(Site.class);

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

        // ---- links
        Ui.heading(c, getString(R.string.sec_links));
        LinearLayout links = Ui.card(c);
        toggle(links, R.string.set_links_in_app, R.string.set_links_in_app_desc, mPrefs.linksInApp(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setLinksInApp(v);
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

        // ---- notification sounds
        Ui.heading(c, getString(R.string.sec_sounds));
        LinearLayout snd = Ui.card(c);
        for (final Site s : Site.values()) {
            mSoundRows.put(s, Ui.clickRow(snd, getString(s == Site.TEAMS ? R.string.set_sound_teams : R.string.set_sound_outlook),
                    soundSummary(s), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            chooseSound(s);
                        }
                    }));
        }
        loudnessRow(snd);
        Ui.para(c, getString(R.string.set_sounds_note));

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
        mReliabilityRow = Ui.clickRow(bg, getString(R.string.set_reliability), reliabilitySummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        java.util.List<Reliability.Problem> ps = Reliability.check(SettingsActivity.this);
                        if (ps.isEmpty()) {
                            startActivity(new Intent(SettingsActivity.this, SetupActivity.class)
                                    .putExtra(SetupActivity.EXTRA_FROM_MENU, true));
                        } else {
                            Reliability.fix(SettingsActivity.this, ps.get(0));
                        }
                    }
                });
        toggle(bg, R.string.set_instant, R.string.set_instant_desc, mPrefs.instantNotifications(), new Toggle() {
            @Override
            public void set(boolean v) {
                mPrefs.setInstantNotifications(v);
                TabManager.get(SettingsActivity.this).applyAwakeSetting();
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

    private String soundSummary(Site s) {
        return SoundPlayer.soundTitle(this, s, getString(R.string.sound_default), getString(R.string.sound_silent));
    }

    private void chooseSound(final Site s) {
        String[] options = {getString(R.string.sound_phone_sounds), getString(R.string.sound_from_file),
                getString(R.string.sound_default), getString(R.string.sound_silent), getString(R.string.sound_test)};
        new AlertDialog.Builder(this)
                .setTitle(s == Site.TEAMS ? R.string.set_sound_teams : R.string.set_sound_outlook)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mSoundSite = s;
                        switch (which) {
                            case 0: {
                                android.net.Uri cur = SoundPlayer.soundUri(SettingsActivity.this, s);
                                Intent i = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                                android.media.RingtoneManager.TYPE_NOTIFICATION)
                                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                                Settings.System.DEFAULT_NOTIFICATION_URI)
                                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur);
                                try {
                                    startActivityForResult(i, REQ_RINGTONE);
                                } catch (Throwable t) {
                                    Toast.makeText(SettingsActivity.this, R.string.sound_picker_missing, Toast.LENGTH_LONG).show();
                                }
                                break;
                            }
                            case 1: {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("audio/*");
                                try {
                                    startActivityForResult(i, REQ_SOUND_FILE);
                                } catch (Throwable t) {
                                    Toast.makeText(SettingsActivity.this, R.string.sound_picker_missing, Toast.LENGTH_LONG).show();
                                }
                                break;
                            }
                            case 2:
                                setSound(s, "");
                                break;
                            case 3:
                                setSound(s, "none");
                                break;
                            default:
                                SoundPlayer.test(SettingsActivity.this, s);
                                break;
                        }
                    }
                }).show();
    }

    private void setSound(Site s, String value) {
        mPrefs.setNotificationSound(s, value);
        TextView row = mSoundRows.get(s);
        if (row != null) row.setText(soundSummary(s));
        if (!"none".equals(value)) SoundPlayer.test(this, s);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || mSoundSite == null) return;
        if (requestCode == REQ_RINGTONE) {
            android.net.Uri picked = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (picked == null) setSound(mSoundSite, "none");
            else if (picked.equals(Settings.System.DEFAULT_NOTIFICATION_URI)) setSound(mSoundSite, "");
            else setSound(mSoundSite, picked.toString());
        } else if (requestCode == REQ_SOUND_FILE && data.getData() != null) {
            android.net.Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            setSound(mSoundSite, u.toString());
        }
    }

    /** "Notification loudness" slider: 100% - 400% of the sound file, in 25% steps. */
    private void loudnessRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText(R.string.set_loudness);
        title.setTextSize(16);
        title.setTextColor(getColor(R.color.wd_text));
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final TextView value = new TextView(this);
        value.setTextSize(16);
        value.setTextColor(getColor(R.color.wd_primary));
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setText(getString(R.string.percent_value, mPrefs.soundBoost()));
        top.addView(value);
        row.addView(top);
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(12);
        bar.setProgress((mPrefs.soundBoost() - 100) / 25);
        bar.setContentDescription(getString(R.string.set_loudness));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int progress, boolean fromUser) {
                value.setText(getString(R.string.percent_value, 100 + progress * 25));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
                mPrefs.setSoundBoost(100 + b.getProgress() * 25);
                SoundPlayer.test(SettingsActivity.this, Site.TEAMS);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 6);
        row.addView(bar, blp);
        TextView desc = new TextView(this);
        desc.setText(R.string.set_loudness_desc);
        desc.setTextSize(13);
        desc.setTextColor(getColor(R.color.wd_text2));
        row.addView(desc);
        parent.addView(row);
    }

    private String reliabilitySummary() {
        java.util.List<Reliability.Problem> ps = Reliability.check(this);
        if (ps.isEmpty()) return getString(R.string.set_reliability_ok);
        String t = Reliability.text(this, ps.get(0));
        return getString(R.string.set_reliability_bad, Character.toUpperCase(t.charAt(0)) + t.substring(1));
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
        if (mReliabilityRow != null) mReliabilityRow.setText(reliabilitySummary());
    }
}
