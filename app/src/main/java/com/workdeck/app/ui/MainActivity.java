package com.workdeck.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Rational;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.UiState;
import com.workdeck.app.WorkDeckApp;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.notify.Notifier;
import com.workdeck.app.service.KeepAliveService;
import com.workdeck.app.web.BrowserTab;
import com.workdeck.app.web.TabHost;
import com.workdeck.app.web.TabManager;
import com.workdeck.app.web.UrlRouter;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single main screen: Teams/Outlook tabs, call controls, zoom and view mode. */
public final class MainActivity extends Activity implements TabHost, CallManager.Listener {
    public static final String ACTION_OPEN_SITE = "com.workdeck.app.OPEN_SITE";
    public static final String ACTION_INCOMING_CALL = "com.workdeck.app.INCOMING_CALL";
    public static final String ACTION_ANSWER_CALL = "com.workdeck.app.ANSWER_CALL";
    public static final String EXTRA_SITE = "site";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";

    private static final int REQ_FILE = 100;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Prefs mPrefs;
    private TabManager mTabs;
    private CallManager mCall;
    private Site mSite;

    private View mContent;
    private FrameLayout mWebContainer;
    private View mErrorPanel;
    private TextView mErrorDetail;
    private View mPopupPanel;
    private View mPopupHeader;
    private FrameLayout mPopupContainer;
    private TextView mPopupTitle;
    private ProgressBar mProgress;
    private View mZoomBar;
    private View mBottomBar;
    private ImageButton mBtnAudio;
    private ImageButton mBtnView;
    private ImageButton mBtnShowBar;
    private TextView mTextSize;
    private Button mDesktopWidth;
    private FrameLayout mFullscreen;
    private final Map<Site, View> mTabButtons = new HashMap<>();
    private final Map<Site, TextView> mBadges = new HashMap<>();

    private final List<WebView> mPopups = new ArrayList<>();
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private ValueCallback<Uri[]> mFileCallback;
    private final Map<Integer, Runnable> mPermissionCallbacks = new HashMap<>();
    private int mNextPermissionCode = 200;
    private boolean mInPip;
    private boolean mShownForCall;
    private String mEnabledKey;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = Prefs.get(this);
        if (!mPrefs.setupDone()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        if (!TabManager.webViewAvailable(this)) {
            showWebViewMissing();
            return;
        }
        WorkDeckApp.setMainActivity(this);
        mEnabledKey = enabledKey();
        setContentView(R.layout.activity_main);
        bindViews();
        setupInsets();

        mTabs = TabManager.get(this);
        mCall = CallManager.get(this);
        mTabs.setHost(this);
        mTabs.startAll();
        for (BrowserTab t : mTabs.enabledTabs()) t.attach(mWebContainer);

        Site initial = Site.fromKey(mPrefs.lastSite());
        if (initial == null || mTabs.tab(initial) == null) initial = firstEnabled();
        showSite(initial);
        mCall.addListener(this);
        onCallStateChanged(mCall.inCall(), mCall.route());
        handleIntent(getIntent());
    }

    private String enabledKey() {
        StringBuilder sb = new StringBuilder();
        for (Site s : Site.values()) sb.append(mPrefs.siteEnabled(s) ? '1' : '0');
        return sb.toString();
    }

    private Site firstEnabled() {
        for (Site s : Site.values()) if (mPrefs.siteEnabled(s)) return s;
        return Site.TEAMS;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mTabs != null) handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        UiState.activityVisible = true;
        KeepAliveService.onUiVisibilityChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mTabs == null) return;
        if (!enabledKey().equals(mEnabledKey)) {
            // Teams/Outlook was switched on or off in Setup: rebuild the screen.
            recreate();
            return;
        }
        UiState.activityResumed = true;
        UiState.visibleSite = mSite;
        if (mPrefs.keepAlive() || mCall.inCall()) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        if (mSite != null) Notifier.get(this).clearSite(mSite);
        if (mSite == Site.TEAMS) Notifier.get(this).cancelCallAlert();
        refreshUi();
    }

    @Override
    protected void onPause() {
        UiState.activityResumed = false;
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onStop() {
        UiState.activityVisible = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mTabs != null) {
            mCall.removeListener(this);
            for (WebView p : new ArrayList<>(mPopups)) closePopup(p);
            hideCustomView();
            for (BrowserTab t : mTabs.enabledTabs()) {
                t.removeListener(mTabListener);
                t.detach();
            }
            mTabs.setHost(null);
            UiState.visibleSite = null;
        }
        WorkDeckApp.setMainActivity(null);
        super.onDestroy();
    }

    private void showWebViewMissing() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.webview_missing_title)
                .setMessage(R.string.webview_missing_text)
                .setPositiveButton(R.string.open_play_store, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.google.android.webview")));
                        } catch (ActivityNotFoundException ignored) {
                        }
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        finish();
                    }
                }).show();
    }

    // ------------------------------------------------------------------ views

    private void bindViews() {
        mContent = findViewById(R.id.content);
        mWebContainer = findViewById(R.id.web_container);
        mErrorPanel = findViewById(R.id.error_panel);
        mErrorDetail = findViewById(R.id.error_detail);
        mPopupPanel = findViewById(R.id.popup_panel);
        mPopupHeader = findViewById(R.id.popup_header);
        mPopupContainer = findViewById(R.id.popup_container);
        mPopupTitle = findViewById(R.id.popup_title);
        mProgress = findViewById(R.id.progress);
        mZoomBar = findViewById(R.id.zoom_bar);
        mBottomBar = findViewById(R.id.bottom_bar);
        mBtnAudio = findViewById(R.id.btn_audio);
        mBtnView = findViewById(R.id.btn_view);
        mBtnShowBar = findViewById(R.id.btn_show_bar);
        mTextSize = findViewById(R.id.text_size);
        mDesktopWidth = findViewById(R.id.desktop_width);
        mFullscreen = findViewById(R.id.fullscreen_container);
        mTabButtons.put(Site.TEAMS, findViewById(R.id.tab_teams));
        mTabButtons.put(Site.OUTLOOK, findViewById(R.id.tab_outlook));
        mBadges.put(Site.TEAMS, (TextView) findViewById(R.id.badge_teams));
        mBadges.put(Site.OUTLOOK, (TextView) findViewById(R.id.badge_outlook));

        for (final Site s : Site.values()) {
            View b = mTabButtons.get(s);
            b.setVisibility(mPrefs.siteEnabled(s) ? View.VISIBLE : View.GONE);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mSite == s) {
                        // Tapping the active tab again closes overlays.
                        if (!mPopups.isEmpty()) closePopup(mPopups.get(mPopups.size() - 1));
                    } else {
                        showSite(s);
                    }
                }
            });
        }
        mBtnView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleViewMode();
            }
        });
        findViewById(R.id.btn_zoom).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mZoomBar.setVisibility(mZoomBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                refreshUi();
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        mBtnAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCall.cycleRoute();
                Toast.makeText(MainActivity.this, routeLabel(mCall.route()), Toast.LENGTH_SHORT).show();
            }
        });
        mBtnAudio.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                chooseRoute();
                return true;
            }
        });
        mBtnShowBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPrefs.setBarHidden(false);
                refreshUi();
            }
        });
        findViewById(R.id.error_retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.reload();
            }
        });
        findViewById(R.id.popup_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mPopups.isEmpty()) closePopup(mPopups.get(mPopups.size() - 1));
            }
        });
        findViewById(R.id.popup_external).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPopups.isEmpty()) return;
                WebView top = mPopups.get(mPopups.size() - 1);
                String url = top.getUrl();
                BrowserTab t = currentTab();
                if (url != null && t != null) t.openExternal(url);
            }
        });

        // Zoom bar
        findViewById(R.id.zoom_in).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.zoomIn();
            }
        });
        findViewById(R.id.zoom_out).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.zoomOut();
            }
        });
        findViewById(R.id.zoom_fit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.zoomFit();
            }
        });
        findViewById(R.id.text_smaller).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.setTextZoom(t.textZoom() - 10);
                refreshUi();
            }
        });
        findViewById(R.id.text_bigger).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.setTextZoom(t.textZoom() + 10);
                refreshUi();
            }
        });
        mDesktopWidth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = mPrefs.desktopWidth();
                int[] w = Prefs.DESKTOP_WIDTHS;
                int next = w[0];
                for (int i = 0; i < w.length; i++) {
                    if (w[i] == cur) {
                        next = w[(i + 1) % w.length];
                        break;
                    }
                }
                mPrefs.setDesktopWidth(next);
                mTabs.refreshLayoutWidth();
                refreshUi();
            }
        });
    }

    /** Edge-to-edge on Android 11+: pad content for system bars, cutouts and the keyboard. */
    private void setupInsets() {
        if (Build.VERSION.SDK_INT < 30) return;
        getWindow().setDecorFitsSystemWindows(false);
        final boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(night ? 0 : light, light);
        }
        mContent.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                        | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, mInPip ? 0 : i.bottom);
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mBtnShowBar.getLayoutParams();
                int base = Math.round(10 * getResources().getDisplayMetrics().density);
                lp.bottomMargin = base + i.bottom;
                lp.rightMargin = base + i.right;
                mBtnShowBar.setLayoutParams(lp);
                return WindowInsets.CONSUMED;
            }
        });
    }

    private BrowserTab currentTab() {
        return mSite == null || mTabs == null ? null : mTabs.tab(mSite);
    }

    private void showSite(Site s) {
        BrowserTab tab = mTabs.tab(s);
        if (tab == null) return;
        tab.ensureStarted();
        tab.attach(mWebContainer);
        mSite = s;
        mPrefs.setLastSite(s);
        UiState.visibleSite = s;
        for (BrowserTab t : mTabs.enabledTabs()) {
            t.addListener(mTabListener);
            if (t.webView() != null) t.webView().setVisibility(t.site == s ? View.VISIBLE : View.GONE);
        }
        if (UiState.activityResumed) Notifier.get(this).clearSite(s);
        refreshUi();
    }

    private final BrowserTab.Listener mTabListener = new BrowserTab.Listener() {
        @Override
        public void onTabChanged(BrowserTab tab) {
            refreshUi();
        }
    };

    private void refreshUi() {
        if (mTabs == null) return;
        BrowserTab cur = currentTab();
        // Re-assert which WebView is shown (a tab rebuilt after a renderer crash starts visible).
        for (BrowserTab t : mTabs.enabledTabs()) {
            WebView w = t.webView();
            if (w == null) continue;
            int want = t.site == mSite ? View.VISIBLE : View.GONE;
            if (w.getVisibility() != want) w.setVisibility(want);
            if (w.getParent() == null) t.attach(mWebContainer);
        }
        for (Site s : Site.values()) {
            View b = mTabButtons.get(s);
            b.setSelected(s == mSite);
            BrowserTab t = mTabs.existing(s);
            int n = t == null ? 0 : t.unreadCount();
            TextView badge = mBadges.get(s);
            if (n > 0) {
                badge.setText(n > 99 ? "99+" : String.valueOf(n));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        }
        if (cur != null) {
            int p = cur.progress();
            mProgress.setVisibility(p < 100 && mPopups.isEmpty() ? View.VISIBLE : View.GONE);
            mProgress.setProgress(p);
            String err = cur.error();
            mErrorPanel.setVisibility(err != null ? View.VISIBLE : View.GONE);
            if (err != null) mErrorDetail.setText(err);
            mBtnView.setImageResource(cur.isDesktop() ? R.drawable.ic_desktop : R.drawable.ic_mobile);
            mTextSize.setText(getString(R.string.text_size_value, cur.textZoom()));
            mDesktopWidth.setText(getString(R.string.desktop_width_value, mPrefs.desktopWidth()));
            mDesktopWidth.setVisibility(cur.isDesktop() ? View.VISIBLE : View.GONE);
        }
        boolean barHidden = mPrefs.barHidden() || mInPip;
        mBottomBar.setVisibility(barHidden ? View.GONE : View.VISIBLE);
        if (barHidden) mZoomBar.setVisibility(View.GONE);
        mBtnShowBar.setVisibility(mPrefs.barHidden() && !mInPip ? View.VISIBLE : View.GONE);
        mPopupHeader.setVisibility(mInPip ? View.GONE : View.VISIBLE);
    }

    private void toggleViewMode() {
        BrowserTab t = currentTab();
        if (t == null) return;
        boolean desktop = !t.isDesktop();
        t.setDesktop(desktop);
        int msg = desktop ? R.string.view_desktop_now
                : (t.site == Site.TEAMS ? R.string.view_teams_mobile_note : R.string.view_mobile_now);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private void showMenu(View anchor) {
        final BrowserTab t = currentTab();
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, R.string.menu_reload);
        m.add(0, 2, 0, R.string.menu_home);
        m.add(0, 3, 0, t != null && t.isDesktop() ? R.string.menu_switch_mobile : R.string.menu_switch_desktop);
        m.add(0, 4, 0, R.string.menu_hide_bar);
        m.add(0, 5, 0, R.string.menu_open_browser);
        m.add(0, 6, 0, R.string.menu_setup);
        m.add(0, 7, 0, R.string.menu_settings);
        m.add(0, 8, 0, R.string.menu_quit);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        if (t != null) t.reload();
                        return true;
                    case 2:
                        if (t != null) t.loadHome();
                        return true;
                    case 3:
                        toggleViewMode();
                        return true;
                    case 4:
                        mPrefs.setBarHidden(true);
                        Toast.makeText(MainActivity.this, R.string.bar_hidden_hint, Toast.LENGTH_LONG).show();
                        refreshUi();
                        return true;
                    case 5:
                        if (t != null && t.currentUrl() != null) t.openExternal(t.currentUrl());
                        return true;
                    case 6:
                        startActivity(new Intent(MainActivity.this, SetupActivity.class)
                                .putExtra(SetupActivity.EXTRA_FROM_MENU, true));
                        return true;
                    case 7:
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        return true;
                    case 8:
                        WorkDeckApp.quit(MainActivity.this);
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    // ------------------------------------------------------------------ intents

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Site site = Site.fromKey(intent.getStringExtra(EXTRA_SITE));
        final String notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID);
        if (ACTION_OPEN_SITE.equals(action) && site != null) {
            showSite(site);
            final BrowserTab t = mTabs.tab(site);
            if (t != null && notificationId != null && !notificationId.isEmpty()) {
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        t.evaluate("window.__wdNotificationClicked && window.__wdNotificationClicked("
                                + JSONObject.quote(notificationId) + ")");
                    }
                }, 300);
            }
        } else if (ACTION_INCOMING_CALL.equals(action)) {
            showOverLockScreen(true);
            showSite(Site.TEAMS);
        } else if (ACTION_ANSWER_CALL.equals(action)) {
            showOverLockScreen(true);
            Notifier.get(this).cancelCallAlert();
            showSite(Site.TEAMS);
            final BrowserTab t = mTabs.tab(Site.TEAMS);
            if (t != null) {
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        t.evaluate("window.__wdCallAction && window.__wdCallAction('accept')");
                        mMain.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!mCall.inCall()) {
                                    Toast.makeText(MainActivity.this, R.string.tap_accept_in_teams,
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        }, 5500);
                    }
                }, 500);
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            openExternalLink(intent.getData().toString());
        }
        // Consume one-shot actions so a rotation/recreate doesn't repeat them.
        intent.setAction(Intent.ACTION_MAIN);
    }

    /** A Teams/Outlook/mailto link opened from another app. */
    private void openExternalLink(String url) {
        if (url.startsWith("msteams:")) url = UrlRouter.msteamsToHttps(url);
        if (url.startsWith("mailto:")) {
            if (mTabs.tab(Site.OUTLOOK) != null) {
                switchToSite(Site.OUTLOOK, UrlRouter.mailtoToOutlook(url, mPrefs.homeUrl(Site.OUTLOOK)));
            }
            return;
        }
        Site target = UrlRouter.siteFor(url);
        if (target == null) target = mSite;
        if (target != null && mTabs.tab(target) != null && UrlRouter.isHttp(url)) switchToSite(target, url);
    }

    private void showOverLockScreen(boolean show) {
        mShownForCall = show;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show);
            setTurnScreenOn(show);
        } else {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (show) getWindow().addFlags(flags);
            else getWindow().clearFlags(flags);
        }
    }

    // ------------------------------------------------------------------ calls

    @Override
    public void onCallStateChanged(boolean inCall, CallManager.Route route) {
        if (mBtnAudio == null) return;
        mBtnAudio.setVisibility(inCall ? View.VISIBLE : View.GONE);
        mBtnAudio.setImageResource(routeIcon(route));
        if (inCall) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (mShownForCall) showOverLockScreen(false);
        }
        updatePipParams(inCall);
    }

    private int routeIcon(CallManager.Route r) {
        if (r == null) return R.drawable.ic_speaker;
        switch (r) {
            case EARPIECE: return R.drawable.ic_earpiece;
            case BLUETOOTH: return R.drawable.ic_bluetooth;
            case WIRED: return R.drawable.ic_headset;
            default: return R.drawable.ic_speaker;
        }
    }

    private String routeLabel(CallManager.Route r) {
        if (r == null) return getString(R.string.route_auto);
        switch (r) {
            case EARPIECE: return getString(R.string.route_earpiece);
            case BLUETOOTH: return getString(R.string.route_bluetooth);
            case WIRED: return getString(R.string.route_wired);
            default: return getString(R.string.route_speaker);
        }
    }

    private void chooseRoute() {
        final List<CallManager.Route> routes = mCall.availableRoutes();
        String[] labels = new String[routes.size()];
        int checked = -1;
        for (int i = 0; i < routes.size(); i++) {
            labels[i] = routeLabel(routes.get(i));
            if (routes.get(i) == mCall.route()) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.choose_audio_output)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mCall.setRouteByUser(routes.get(which));
                        d.dismiss();
                    }
                }).show();
    }

    private PictureInPictureParams pipParams(boolean inCall) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(3, 4));
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(inCall && mPrefs.pipOnCall());
        return b.build();
    }

    private void updatePipParams(boolean inCall) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            setPictureInPictureParams(pipParams(inCall));
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (mCall != null && mCall.inCall() && mPrefs.pipOnCall() && Build.VERSION.SDK_INT < 31
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                enterPictureInPictureMode(pipParams(true));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        mInPip = isInPictureInPictureMode;
        refreshUi();
        mContent.requestApplyInsets();
    }

    // ------------------------------------------------------------------ back

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mCustomView != null) {
            hideCustomView();
            return;
        }
        if (!mPopups.isEmpty()) {
            WebView top = mPopups.get(mPopups.size() - 1);
            if (top.canGoBack()) top.goBack();
            else closePopup(top);
            return;
        }
        if (mZoomBar.getVisibility() == View.VISIBLE) {
            mZoomBar.setVisibility(View.GONE);
            return;
        }
        BrowserTab t = currentTab();
        if (t != null && t.canGoBack()) {
            WebBackForwardList list = t.webView().copyBackForwardList();
            int idx = list.getCurrentIndex();
            String prev = idx > 0 ? list.getItemAtIndex(idx - 1).getUrl() : null;
            if (prev != null && !UrlRouter.isLogin(prev)) {
                t.goBack();
                return;
            }
        }
        // Keep everything running: just go to the home screen.
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ TabHost

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void onTabStateChanged(BrowserTab tab) {
        refreshUi();
    }

    @Override
    public void requestRuntimePermissions(String[] permissions, Runnable done) {
        int code = mNextPermissionCode++;
        mPermissionCallbacks.put(code, done);
        requestPermissions(permissions, code);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Runnable r = mPermissionCallbacks.remove(requestCode);
        if (r != null) r.run();
        KeepAliveService.onUiVisibilityChanged();
    }

    @Override
    public boolean showFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (mFileCallback != null) mFileCallback.onReceiveValue(null);
        mFileCallback = callback;
        try {
            Intent i = params.createIntent();
            if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            startActivityForResult(Intent.createChooser(i, null), REQ_FILE);
            return true;
        } catch (Throwable t) {
            mFileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || mFileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[] {data.getData()};
            }
        }
        mFileCallback.onReceiveValue(result);
        mFileCallback = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void showCustomView(View view, WebChromeClient.CustomViewCallback callback) {
        if (mCustomView != null) {
            callback.onCustomViewHidden();
            return;
        }
        mCustomView = view;
        mCustomCallback = callback;
        mFullscreen.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFullscreen.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void hideCustomView() {
        if (mCustomView == null) return;
        mFullscreen.removeView(mCustomView);
        mFullscreen.setVisibility(View.GONE);
        mCustomView = null;
        if (mCustomCallback != null) mCustomCallback.onCustomViewHidden();
        mCustomCallback = null;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.systemBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    @Override
    public boolean openPopup(BrowserTab opener, Message resultMsg, boolean userGesture) {
        if (mPopups.size() >= 4) return false;
        WebView popup = opener.createPopupWebView(this);
        mPopups.add(popup);
        popup.setVisibility(View.INVISIBLE);
        mPopupContainer.addView(popup);
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    @Override
    public void showPopup(WebView popup) {
        if (!mPopups.contains(popup)) return;
        for (WebView p : mPopups) p.setVisibility(p == popup ? View.VISIBLE : View.GONE);
        mPopupTitle.setText(UrlRouter.host(popup.getUrl() == null ? "" : popup.getUrl()));
        mPopupPanel.setVisibility(View.VISIBLE);
        refreshUi();
    }

    @Override
    public void onPopupTitle(WebView popup, String title) {
        if (!mPopups.isEmpty() && mPopups.get(mPopups.size() - 1) == popup && title != null) {
            mPopupTitle.setText(title);
        }
    }

    @Override
    public void closePopup(final WebView popup) {
        if (!mPopups.remove(popup)) return;
        mPopupContainer.removeView(popup);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    popup.destroy();
                } catch (Throwable ignored) {
                }
            }
        });
        WebView top = null;
        for (int i = mPopups.size() - 1; i >= 0; i--) {
            if (mPopups.get(i).getVisibility() != View.INVISIBLE) {
                top = mPopups.get(i);
                break;
            }
        }
        if (top == null) {
            mPopupPanel.setVisibility(View.GONE);
        } else {
            top.setVisibility(View.VISIBLE);
            mPopupTitle.setText(top.getTitle());
        }
        refreshUi();
    }

    @Override
    public void switchToSite(Site site, String url) {
        BrowserTab t = mTabs.tab(site);
        if (t == null) return;
        showSite(site);
        if (url != null) t.loadUrl(url);
    }

    @Override
    public boolean isSiteVisible(Site site) {
        return UiState.activityResumed && mSite == site;
    }
}
