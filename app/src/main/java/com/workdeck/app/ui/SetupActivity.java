package com.workdeck.app.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.notify.Notifier;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.TabManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** First-run setup: account type, which apps, and every permission in one place. */
public final class SetupActivity extends Activity {
    public static final String EXTRA_FROM_MENU = "from_menu";
    private static final int REQ_ALL = 1;
    private static final int REQ_ONE = 2;

    private Prefs mPrefs;
    private boolean mFromMenu;
    private String mInitialAccount;
    private Ui.PermRow mMic, mCamera, mNotif, mBt, mBattery, mFullScreen;
    private CheckBox mTeams, mOutlook;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = Prefs.get(this);
        mFromMenu = getIntent().getBooleanExtra(EXTRA_FROM_MENU, false);
        mInitialAccount = mPrefs.accountType();
        build();
    }

    private void build() {
        LinearLayout c = Ui.page(this, getString(mFromMenu ? R.string.menu_setup : R.string.setup_title), mFromMenu);
        Ui.para(c, getString(R.string.setup_intro));

        // 1. account
        Ui.heading(c, getString(R.string.setup_step_account));
        LinearLayout accountCard = Ui.card(c);
        RadioGroup group = new RadioGroup(this);
        final RadioButton work = new RadioButton(this);
        work.setText(R.string.setup_account_work);
        work.setId(View.generateViewId());
        final RadioButton personal = new RadioButton(this);
        personal.setText(R.string.setup_account_personal);
        personal.setId(View.generateViewId());
        group.addView(work);
        group.addView(personal);
        group.check(Prefs.ACCOUNT_PERSONAL.equals(mPrefs.accountType()) ? personal.getId() : work.getId());
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                mPrefs.setAccountType(id == personal.getId() ? Prefs.ACCOUNT_PERSONAL : Prefs.ACCOUNT_WORK);
            }
        });
        accountCard.addView(group);

        // 2. apps
        Ui.heading(c, getString(R.string.setup_step_apps));
        LinearLayout appsCard = Ui.card(c);
        mTeams = new CheckBox(this);
        mTeams.setText(R.string.site_teams);
        mTeams.setChecked(mPrefs.siteEnabled(Site.TEAMS));
        mOutlook = new CheckBox(this);
        mOutlook.setText(R.string.site_outlook);
        mOutlook.setChecked(mPrefs.siteEnabled(Site.OUTLOOK));
        appsCard.addView(mTeams);
        appsCard.addView(mOutlook);
        CompoundButton.OnCheckedChangeListener keepOne = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!mTeams.isChecked() && !mOutlook.isChecked()) {
                    b.setChecked(true);
                    Toast.makeText(SetupActivity.this, R.string.setup_need_one_app, Toast.LENGTH_SHORT).show();
                }
            }
        };
        mTeams.setOnCheckedChangeListener(keepOne);
        mOutlook.setOnCheckedChangeListener(keepOne);

        // 3. permissions
        Ui.heading(c, getString(R.string.setup_step_permissions));
        Ui.para(c, getString(R.string.setup_permissions_hint));
        Ui.primaryButton(c, getString(R.string.setup_allow_all), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allowEverything();
            }
        });
        LinearLayout perms = Ui.card(c);
        mMic = Ui.permRow(perms, getString(R.string.perm_mic), getString(R.string.perm_mic_desc),
                getString(R.string.btn_allow), request(Manifest.permission.RECORD_AUDIO));
        mCamera = Ui.permRow(perms, getString(R.string.perm_camera), getString(R.string.perm_camera_desc),
                getString(R.string.btn_allow), request(Manifest.permission.CAMERA));
        if (Build.VERSION.SDK_INT >= 33) {
            mNotif = Ui.permRow(perms, getString(R.string.perm_notifications),
                    getString(R.string.perm_notifications_desc), getString(R.string.btn_allow),
                    request(Manifest.permission.POST_NOTIFICATIONS));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            mBt = Ui.permRow(perms, getString(R.string.perm_bluetooth), getString(R.string.perm_bluetooth_desc),
                    getString(R.string.btn_allow), request(Manifest.permission.BLUETOOTH_CONNECT));
        }
        mBattery = Ui.permRow(perms, getString(R.string.perm_battery), getString(R.string.perm_battery_desc),
                getString(R.string.btn_allow), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestBattery();
                    }
                });
        if (Build.VERSION.SDK_INT >= 34) {
            mFullScreen = Ui.permRow(perms, getString(R.string.perm_fullscreen),
                    getString(R.string.perm_fullscreen_desc), getString(R.string.btn_open), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openFullScreenSettings();
                        }
                    });
        }
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String desc = isAggressiveOem(maker) ? getString(R.string.perm_oem_desc, capitalize(maker))
                : getString(R.string.perm_oem_desc_generic);
        Ui.permRow(perms, getString(R.string.perm_oem), desc, getString(R.string.btn_open), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOemSettings();
            }
        }).status.setVisibility(View.GONE);

        Ui.heading(c, getString(R.string.setup_tips_title));
        Ui.para(c, getString(R.string.setup_tips));

        Ui.primaryButton(c, getString(mFromMenu ? R.string.ok : R.string.setup_continue), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishSetup();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryIgnored() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refresh() {
        mMic.set(has(Manifest.permission.RECORD_AUDIO), false);
        mCamera.set(has(Manifest.permission.CAMERA), true);
        if (mNotif != null) mNotif.set(has(Manifest.permission.POST_NOTIFICATIONS), false);
        if (mBt != null) mBt.set(has(Manifest.permission.BLUETOOTH_CONNECT), true);
        mBattery.set(batteryIgnored(), false);
        if (mFullScreen != null) mFullScreen.set(Notifier.canUseFullScreenIntent(this), true);
    }

    private View.OnClickListener request(final String permission) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!has(permission)) {
                    requestPermissions(new String[] {permission}, REQ_ONE);
                }
            }
        };
    }

    private void allowEverything() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (!has(p)) missing.add(p);
        if (missing.isEmpty()) {
            if (!batteryIgnored()) requestBattery();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQ_ALL);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
        boolean anyDenied = false;
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults.length > i && grantResults[i] != PackageManager.PERMISSION_GRANTED
                    && !shouldShowRequestPermissionRationale(permissions[i])) {
                anyDenied = true;
            }
        }
        if (anyDenied && requestCode == REQ_ONE) {
            // Permanently denied: the only way left is the app's system settings page.
            openAppDetails();
            return;
        }
        if (requestCode == REQ_ALL && !batteryIgnored()) requestBattery();
    }

    @SuppressLint("BatteryLife")
    private void requestBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
                openAppDetails();
            }
        }
    }

    private void openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAggressiveOem(String maker) {
        String m = maker.toLowerCase(Locale.ROOT);
        String[] list = {"xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "huawei", "honor", "oneplus",
                "samsung", "asus", "meizu", "tecno", "infinix", "itel", "lenovo", "motorola", "nothing"};
        for (String s : list) if (m.contains(s)) return true;
        return false;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Tries the phone maker's autostart screen, falling back to the app's settings page. */
    private void openOemSettings() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        List<ComponentName> candidates = new ArrayList<>();
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            candidates.add(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        } else if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) {
            candidates.add(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            candidates.add(new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"));
        } else if (m.contains("vivo") || m.contains("iqoo")) {
            candidates.add(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        } else if (m.contains("huawei") || m.contains("honor")) {
            candidates.add(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        }
        for (ComponentName cn : candidates) {
            try {
                startActivity(new Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Throwable ignored) {
            }
        }
        openAppDetails();
    }

    private void finishSetup() {
        mPrefs.setSiteEnabled(Site.TEAMS, mTeams.isChecked());
        mPrefs.setSiteEnabled(Site.OUTLOOK, mOutlook.isChecked());
        mPrefs.setSetupDone(true);
        if (!mInitialAccount.equals(mPrefs.accountType())) {
            for (Site s : Site.values()) {
                BrowserTab t = TabManager.get(this).existing(s);
                if (t != null && t.isCreated()) t.loadHome();
            }
        }
        if (!mFromMenu) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mFromMenu) {
            finishSetup();
        } else {
            super.onBackPressed();
        }
    }
}
