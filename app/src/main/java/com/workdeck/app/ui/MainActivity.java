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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
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

/**
 * The single main screen: one drop-up per account (each with its own Teams and
 * Outlook), call controls, zoom and view mode.
 */
public final class MainActivity extends Activity implements TabHost, CallManager.Listener {
    public static final String ACTION_OPEN_SITE = "com.workdeck.app.OPEN_SITE";
    public static final String ACTION_INCOMING_CALL = "com.workdeck.app.INCOMING_CALL";
    public static final String ACTION_ANSWER_CALL = "com.workdeck.app.ANSWER_CALL";
    public static final String EXTRA_SITE = "site";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";
    /** Files shared to WorkDeck from another app (copied into the cache by ShareActivity). */
    public static final String ACTION_ATTACH = "com.workdeck.app.ATTACH";
    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_MIMES = "mimes";
    public static final String EXTRA_TEXT = "text";

    private static final int REQ_FILE = 100;
    private static final int REQ_ATTACH = 101;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Prefs mPrefs;
    private Accounts mAccounts;
    private TabManager mTabs;
    private CallManager mCall;
    private Account mAccount;
    private Site mSite;
    /** Id of the account's link tab on screen, or null when Teams/Outlook is shown. */
    private String mWebId;
    /** Link tab key -> key of the tab whose link opened it (Back returns there). */
    private final Map<String, String> mOpenedFrom = new HashMap<>();
    private View mTabHeader;
    private TextView mTabTitle;
    private TextView mTabHost;
    private ImageButton mBtnAttach;
    /** Files (and text) waiting for the user to tap a message box in the tab with this key. */
    private List<com.workdeck.app.web.Attachments.Item> mPendingAttach;
    private String mPendingAttachKey;
    private String mPendingText;
    private long mPendingSince;
    /** Files handed to a page and not answered yet (never sent twice). */
    private List<com.workdeck.app.web.Attachments.Item> mInFlight;
    private String mAttachTargetKey;

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
    private HorizontalScrollView mChipScroll;
    private LinearLayout mChips;
    /** Account id -> its chip in the bottom bar. */
    private final Map<String, View> mChipViews = new HashMap<>();

    private final List<WebView> mPopups = new ArrayList<>();
    /** Popup -> key of the tab that opened it (a popup shows only over its own tab). */
    private final Map<WebView, String> mPopupOwner = new HashMap<>();
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private ValueCallback<Uri[]> mFileCallback;
    private final Map<Integer, Runnable> mPermissionCallbacks = new HashMap<>();
    private int mNextPermissionCode = 200;
    private boolean mInPip;
    private boolean mShownForCall;
    private String mAccountsSig;
    private int mNightMode;

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
        mAccounts = Accounts.get(this);
        mAccountsSig = mAccounts.signature();
        mNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        setContentView(R.layout.activity_main);
        bindViews();
        setupInsets();

        mTabs = TabManager.get(this);
        mCall = CallManager.get(this);
        mTabs.setHost(this);
        mTabs.addGlobalListener(mTabListener);
        mTabs.startBackground();
        buildChips();

        Account initial = mAccounts.active();
        showTab(initial, initial.preferredSite());
        mCall.addListener(this);
        onCallStateChanged(mCall.inCall(), mCall.route());
        handleIntent(getIntent());
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
        if (mTabs != null) {
            mTabs.setUiVisible(true);
            refreshUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mTabs == null) return;
        if (!mAccounts.signature().equals(mAccountsSig) || mAccount == null || mAccounts.find(mAccount.id) == null) {
            // Accounts were added, removed, renamed or had Teams/Outlook switched: rebuild.
            recreate();
            return;
        }
        if (mWebId != null && mAccount.webTab(mWebId) == null) showTab(mAccount, mAccount.preferredSite());
        UiState.activityResumed = true;
        UiState.visibleTab = currentKey();
        if (mPrefs.keepAlive() || mCall.inCall()) {
            KeepAliveService.start(this);
        } else {
            KeepAliveService.stop(this);
        }
        clearNotificationsOfCurrent();
        refreshUi();
        updateReliabilityBanner();
    }

    /** A bar on top when phone settings will delay or stop notifications, with a one-tap fix. */
    private void updateReliabilityBanner() {
        View bar = findViewById(R.id.reliability_banner);
        if (bar == null) return;
        final List<Reliability.Problem> problems = Reliability.check(this);
        if (problems.isEmpty() || System.currentTimeMillis() < mPrefs.reliabilitySnoozedUntil()) {
            bar.setVisibility(View.GONE);
            return;
        }
        final Reliability.Problem p = problems.get(0);
        ((TextView) findViewById(R.id.reliability_text)).setText(getString(R.string.rel_banner, Reliability.text(this, p)));
        findViewById(R.id.reliability_fix).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Reliability.fix(MainActivity.this, p);
                updateReliabilityBanner();
            }
        });
        findViewById(R.id.reliability_later).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPrefs.snoozeReliability(System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000);
                updateReliabilityBanner();
            }
        });
        bar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        UiState.activityResumed = false;
        android.util.Log.i("WorkDeck/Main", "paused");
        if (mTabs != null) mTabs.flushCookies();
        super.onPause();
    }

    @Override
    protected void onStop() {
        UiState.activityVisible = false;
        if (mTabs != null) {
            BrowserTab cur = currentTab();
            if (cur != null) cur.setDrawn(false);
            mTabs.setUiVisible(false); // keeps every page awake while WorkDeck isn't on screen
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mTabs != null) {
            mCall.removeListener(this);
            for (WebView p : new ArrayList<>(mPopups)) closePopup(p);
            hideCustomView();
            mTabs.removeGlobalListener(mTabListener);
            for (BrowserTab t : mTabs.allTabs()) t.detach();
            mTabs.setHost(null);
            UiState.visibleTab = null;
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
        mChipScroll = findViewById(R.id.account_scroll);
        mChips = findViewById(R.id.account_chips);
        mBtnAttach = findViewById(R.id.btn_attach);
        mBtnAttach.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFilesToAttach();
            }
        });
        mTabHeader = findViewById(R.id.tab_header);
        mTabTitle = findViewById(R.id.tab_title);
        mTabHost = findViewById(R.id.tab_host);
        findViewById(R.id.tab_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAccount != null && mWebId != null) closeWebTab(mAccount, mWebId);
            }
        });
        findViewById(R.id.tab_reload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null) t.reload();
            }
        });
        findViewById(R.id.tab_external).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrowserTab t = currentTab();
                if (t != null && t.currentUrl() != null) t.openExternal(t.currentUrl());
            }
        });
        findViewById(R.id.btn_add_account).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addAccount();
            }
        });
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
                WebView top = currentPopup();
                if (top != null) closePopup(top);
            }
        });
        findViewById(R.id.popup_external).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WebView top = currentPopup();
                if (top == null) return;
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
        if (mAccount == null || mTabs == null) return null;
        if (mWebId != null) return mTabs.webTab(mAccount, mAccount.webTab(mWebId));
        return mSite == null ? null : mTabs.tab(mAccount, mSite);
    }

    private String currentKey() {
        if (mAccount == null) return null;
        if (mWebId != null) return mAccount.webKey(mWebId);
        return mSite == null ? null : mAccount.key(mSite);
    }

    private void clearNotificationsOfCurrent() {
        String key = currentKey();
        if (key == null) return;
        Notifier n = Notifier.get(this);
        n.clearTab(key);
        if (key.equals(n.callAlertTab())) n.cancelCallAlert();
    }

    /** Shows an account's Teams or Outlook (the other one if that app is switched off). */
    private void showTab(Account a, Site s) {
        if (a == null) return;
        if (s == null || !a.siteEnabled(s)) s = a.preferredSite();
        BrowserTab tab = mTabs.tab(a, s);
        if (tab == null) return;
        tab.ensureStarted();
        tab.attach(mWebContainer);
        boolean firstOpen = true;
        for (Site o : Site.values()) if (a.wasOpened(o)) firstOpen = false;
        mAccount = a;
        mSite = s;
        mWebId = null;
        a.lastSite = s;
        a.markOpened(s);
        if (firstOpen) {
            // First time this account is used: also start its other app shortly, so its
            // Outlook (or Teams) notifications work without opening it first.
            for (Site o : Site.values()) if (a.siteEnabled(o)) a.markOpened(o);
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mTabs != null) mTabs.startBackground();
                }
            }, 4000);
        }
        mAccounts.setActive(a);
        mAccounts.save();
        becameVisible(tab);
        scrollChipIntoView(a);
    }

    private void becameVisible(BrowserTab tab) {
        tab.markShown();
        UiState.visibleTab = tab.key;
        layoutTabs(tab);
        if (UiState.activityResumed) clearNotificationsOfCurrent();
        updatePopupPanel();
        refreshUi();
    }

    // ------------------------------------------------------------------ attaching phone files

    private void pickFilesToAttach() {
        BrowserTab t = currentTab();
        if (t == null) return;
        mAttachTargetKey = t.key;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.attach_pick_title)), REQ_ATTACH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.attach_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private static List<Uri> urisOf(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) out.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    private void attachTo(BrowserTab t, List<com.workdeck.app.web.Attachments.Item> items) {
        if (t == null || items.isEmpty()) return;
        Toast.makeText(this, getResources().getQuantityString(R.plurals.attach_adding, items.size(), items.size()),
                Toast.LENGTH_SHORT).show();
        mInFlight = items;
        mPendingAttachKey = t.key;
        t.attach(items);
    }

    /** Hands over what was waiting (exactly once: the waiting list is emptied right away). */
    private void deliverPending(final BrowserTab t) {
        if (mPendingAttach == null && mPendingText == null) return;
        final List<com.workdeck.app.web.Attachments.Item> items = mPendingAttach;
        final String text = mPendingText;
        mPendingAttach = null;
        mPendingText = null;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (text != null) t.insertText(text);
                if (items != null) attachTo(t, items);
            }
        }, 400);
    }

    @Override
    public void onAttachResult(BrowserTab tab, String method, int count, boolean seen) {
        List<com.workdeck.app.web.Attachments.Item> sent = mInFlight;
        mInFlight = null;
        if ("none".equals(method)) {
            // No message box open yet: keep the files until the user taps one.
            mPendingAttach = sent;
            mPendingAttachKey = tab.key;
            mPendingSince = android.os.SystemClock.elapsedRealtime();
            Toast.makeText(this, R.string.attach_open_box, Toast.LENGTH_LONG).show();
            refreshUi();
            return;
        }
        if ("readfailed".equals(method)) {
            Toast.makeText(this, R.string.attach_read_failed, Toast.LENGTH_LONG).show();
        } else if ("unavailable".equals(method)) {
            Toast.makeText(this, R.string.attach_unavailable, Toast.LENGTH_LONG).show();
        } else if (seen) {
            Toast.makeText(this, getResources().getQuantityString(R.plurals.attach_done, count, count), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getResources().getQuantityString(R.plurals.attach_handed, count, count), Toast.LENGTH_LONG).show();
        }
        refreshUi();
    }

    /** Files (and/or text) shared to WorkDeck from another app. */
    private void handleShared(Intent intent, Account account, Site site) {
        String[] paths = intent.getStringArrayExtra(EXTRA_FILES);
        String[] names = intent.getStringArrayExtra(EXTRA_NAMES);
        String[] mimes = intent.getStringArrayExtra(EXTRA_MIMES);
        String text = intent.getStringExtra(EXTRA_TEXT);
        showTab(account, site);
        final BrowserTab t = currentTab();
        if (t == null) return;
        final List<com.workdeck.app.web.Attachments.Item> items = new ArrayList<>();
        for (int i = 0; paths != null && i < paths.length; i++) {
            java.io.File f = new java.io.File(paths[i]);
            if (!f.exists()) continue;
            items.add(com.workdeck.app.web.Attachments.add(f, names != null && i < names.length ? names[i] : f.getName(),
                    mimes != null && i < mimes.length ? mimes[i] : null));
        }
        mPendingAttach = items.isEmpty() ? null : items;
        mPendingAttachKey = t.key;
        mPendingSince = android.os.SystemClock.elapsedRealtime();
        mPendingText = text == null || text.trim().isEmpty() ? null : text;
        if (mPendingAttach == null && mPendingText == null) return;
        // Give a page that was just opened a moment; otherwise the files wait for the message box.
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (t.key.equals(mPendingAttachKey)) deliverPending(t); // no-op if already handed over
            }
        }, t.progress() >= 100 ? 600 : 4000);
        Toast.makeText(this, R.string.attach_shared_hint, Toast.LENGTH_LONG).show();
        refreshUi();
    }

    // ------------------------------------------------------------------ link tabs

    /** Shows one of an account's link tabs. */
    private void showWebTab(Account a, String webId) {
        Account.WebTab w = a == null ? null : a.webTab(webId);
        if (w == null) return;
        BrowserTab tab = mTabs.webTab(a, w);
        tab.ensureStarted();
        tab.attach(mWebContainer);
        mAccount = a;
        mWebId = webId;
        mAccounts.setActive(a);
        mAccounts.save();
        becameVisible(tab);
        scrollChipIntoView(a);
    }

    /** Opens a URL as a new link tab of the account and shows it. */
    private void openNewTab(Account a, String url, BrowserTab from) {
        if (a == null || url == null || url.isEmpty()) return;
        BrowserTab t = mTabs.openWebTab(a, url);
        if (from != null) mOpenedFrom.put(t.key, from.key);
        t.ensureStarted();
        showWebTab(a, t.web.id);
    }

    @Override
    public void openLink(BrowserTab from, String url) {
        openNewTab(from.account, url, from);
    }

    /** The × of a link tab: close it and go back to where it was opened from. */
    private void closeWebTab(Account a, String webId) {
        String key = a.webKey(webId);
        boolean wasShown = key.equals(currentKey());
        String from = mOpenedFrom.remove(key);
        mTabs.closeWebTab(a, webId);
        if (!wasShown) {
            refreshUi();
            return;
        }
        returnFromWebTab(a, from);
    }

    private void returnFromWebTab(Account a, String fromKey) {
        BrowserTab from = fromKey == null ? null : mTabs.existing(fromKey);
        if (from != null && from.account.id.equals(a.id)) {
            if (from.isWeb()) showWebTab(a, from.web.id);
            else showTab(a, from.site);
        } else {
            showTab(a, a.preferredSite());
        }
    }

    private void showNewTabDialog(final Account a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.new_tab_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        box.addView(input);
        final AlertDialog[] dlg = new AlertDialog[1];
        String[][] quick = a.isPersonal()
                ? new String[][] {{getString(R.string.quick_m365), "https://www.microsoft365.com/"},
                        {getString(R.string.quick_onedrive), "https://onedrive.live.com/"},
                        {getString(R.string.quick_calendar), "https://outlook.live.com/calendar/"}}
                : new String[][] {{getString(R.string.quick_m365), "https://www.microsoft365.com/"},
                        {getString(R.string.quick_onedrive), "https://www.office.com/launch/onedrive"},
                        {getString(R.string.quick_sharepoint), "https://www.office.com/launch/sharepoint"},
                        {getString(R.string.quick_calendar), "https://outlook.office.com/calendar/"}};
        TextView label = new TextView(this);
        label.setText(R.string.new_tab_quick);
        label.setTextColor(getColor(R.color.wd_text2));
        label.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        box.addView(label);
        for (final String[] q : quick) {
            TextView row = new TextView(this);
            row.setText(q[0]);
            row.setTextSize(16);
            row.setTextColor(getColor(R.color.wd_primary));
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (dlg[0] != null) dlg[0].dismiss();
                    openTypedOrQuick(a, q[1]);
                }
            });
            box.addView(row);
        }
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.new_tab_title, a.name))
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.new_tab_open, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openTypedOrQuick(a, UrlRouter.fromUserInput(input.getText().toString()));
                    }
                }).create();
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                dlg[0].dismiss();
                openTypedOrQuick(a, UrlRouter.fromUserInput(input.getText().toString()));
                return true;
            }
        });
        dlg[0].show();
    }

    /** Teams/Outlook addresses open in the account's Teams/Outlook; everything else in a new tab. */
    private void openTypedOrQuick(Account a, String url) {
        if (url == null || url.isEmpty()) return;
        Site s = UrlRouter.siteFor(url);
        if (s != null && a.siteEnabled(s)) openIn(a, s, url);
        else openNewTab(a, url, currentTab());
    }

    private final BrowserTab.Listener mTabListener = new BrowserTab.Listener() {
        @Override
        public void onTabChanged(BrowserTab tab) {
            refreshUi();
        }
    };

    // ------------------------------------------------------------------ account chips (drop-ups)

    private void buildChips() {
        mChips.removeAllViews();
        mChipViews.clear();
        LayoutInflater inf = getLayoutInflater();
        for (final Account a : mAccounts.list()) {
            View chip = inf.inflate(R.layout.account_chip, mChips, false);
            ((TextView) chip.findViewById(R.id.chip_name)).setText(a.name);
            chip.setContentDescription(getString(R.string.desc_account_chip, a.name));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAccountMenu(a, v);
                }
            });
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    // Quick switch: straight to the app this account used last.
                    showTab(a, a.preferredSite());
                    return true;
                }
            });
            mChips.addView(chip);
            mChipViews.put(a.id, chip);
        }
    }

    private void addAccount() {
        AccountDialogs.showAdd(this, new AccountDialogs.Added() {
            @Override
            public void onAdded(Account a) {
                mAccountsSig = mAccounts.signature();
                buildChips();
                showTab(a, Site.TEAMS);
                Toast.makeText(MainActivity.this, getString(R.string.acc_added, a.name), Toast.LENGTH_LONG).show();
            }
        });
    }

    private Drawable siteIcon(Site s, int tint) {
        Drawable d = getDrawable(s == Site.TEAMS ? R.drawable.ic_tab_teams : R.drawable.ic_tab_outlook).mutate();
        d.setTint(tint);
        return d;
    }

    private int unread(Account a, Site s) {
        BrowserTab t = mTabs.existing(a.key(s));
        return t == null ? 0 : Math.max(0, t.unreadCount());
    }

    private Drawable tinted(int res, int tint) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(tint);
        return d;
    }

    /**
     * The drop-up of an account: its Teams and Outlook (with unread counts), the links
     * opened in it (each with a × to close), "New tab" and its settings.
     */
    private void showAccountMenu(final Account a, View anchor) {
        int accent = AccountDialogs.accent(this, a);
        boolean thisAccount = mAccount != null && a.id.equals(mAccount.id);
        List<DropUp.Item> items = DropUp.list();
        for (final Site s : Site.values()) {
            if (!a.siteEnabled(s)) continue;
            int n = unread(a, s);
            DropUp.Item it = new DropUp.Item();
            it.icon = siteIcon(s, accent);
            it.title = getString(s.titleRes) + (n > 0 ? "  (" + (n > 99 ? "99+" : n) + ")" : "");
            it.checked = thisAccount && mWebId == null && s == mSite;
            it.checkColor = accent;
            it.onClick = new Runnable() {
                @Override
                public void run() {
                    if (a == mAccount && mWebId == null && s == mSite) {
                        // Picking the open app again closes any window on top of it.
                        WebView top = currentPopup();
                        if (top != null) closePopup(top);
                    } else {
                        showTab(a, s);
                    }
                }
            };
            items.add(it);
        }
        boolean first = true;
        for (final Account.WebTab w : a.webTabs()) {
            final BrowserTab t = mTabs.webTab(a, w);
            DropUp.Item it = new DropUp.Item();
            it.icon = tinted(R.drawable.ic_globe, accent);
            it.title = t.displayTitle();
            it.checked = thisAccount && w.id.equals(mWebId);
            it.checkColor = accent;
            it.dividerBefore = first;
            first = false;
            it.onClick = new Runnable() {
                @Override
                public void run() {
                    showWebTab(a, w.id);
                }
            };
            it.onClose = new Runnable() {
                @Override
                public void run() {
                    closeWebTab(a, w.id);
                }
            };
            it.closeDescription = getString(R.string.desc_close_named, t.displayTitle());
            items.add(it);
        }
        DropUp.Item newTab = new DropUp.Item();
        newTab.icon = tinted(R.drawable.ic_add, getColor(R.color.wd_icon));
        newTab.title = getString(R.string.menu_new_tab);
        newTab.dividerBefore = true;
        newTab.onClick = new Runnable() {
            @Override
            public void run() {
                showNewTabDialog(a);
            }
        };
        items.add(newTab);
        DropUp.Item settings = new DropUp.Item();
        settings.icon = tinted(R.drawable.ic_tune, getColor(R.color.wd_icon));
        settings.title = getString(R.string.menu_account_settings, a.name);
        settings.onClick = new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, AccountsActivity.class)
                        .putExtra(AccountsActivity.EXTRA_ACCOUNT, a.id));
            }
        };
        items.add(settings);
        DropUp.show(this, anchor, items);
    }

    private void scrollChipIntoView(Account a) {
        final View chip = mChipViews.get(a.id);
        if (chip == null || mChipScroll == null) return;
        mChipScroll.post(new Runnable() {
            @Override
            public void run() {
                int x = mChipScroll.getScrollX();
                int w = mChipScroll.getWidth();
                if (chip.getLeft() < x) mChipScroll.smoothScrollTo(chip.getLeft(), 0);
                else if (chip.getRight() > x + w) mChipScroll.smoothScrollTo(chip.getRight() - w, 0);
            }
        });
    }

    private void refreshChips() {
        boolean night = AccountDialogs.isNight(this);
        for (Account a : mAccounts.list()) {
            View chip = mChipViews.get(a.id);
            if (chip == null) continue;
            boolean active = mAccount != null && a.id.equals(mAccount.id);
            int accent = AccountDialogs.accent(this, a);
            chip.setSelected(active);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 18));
            bg.setColor(active ? (accent & 0x00FFFFFF) | (night ? 0x33000000 : 0x24000000) : 0);
            chip.setBackground(bg);
            // Every account keeps its colour; the one on screen is highlighted and shows
            // the icon of the app (Teams / Outlook) that is open.
            TextView name = chip.findViewById(R.id.chip_name);
            name.setTextColor(accent);
            name.setAlpha(active ? 1f : 0.8f);
            ImageView icon = chip.findViewById(R.id.chip_icon);
            if (active && mWebId != null) {
                icon.setImageDrawable(tinted(R.drawable.ic_globe, accent));
                icon.setVisibility(View.VISIBLE);
            } else if (active && mSite != null) {
                icon.setImageDrawable(siteIcon(mSite, accent));
                icon.setVisibility(View.VISIBLE);
            } else {
                icon.setVisibility(View.GONE);
            }
            int n = mTabs.unreadOf(a);
            TextView badge = chip.findViewById(R.id.chip_badge);
            if (n > 0) {
                badge.setText(n > 99 ? "99+" : String.valueOf(n));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Only the tab on screen is attached; every other tab is parked off screen, where it
     * stays awake (instant notifications) without being drawn.
     */
    private void layoutTabs(BrowserTab cur) {
        for (BrowserTab t : mTabs.allTabs()) {
            WebView w = t.webView();
            if (w == null) continue;
            if (t == cur) {
                if (w.getParent() != mWebContainer) t.attach(mWebContainer);
                if (w.getVisibility() != View.VISIBLE) w.setVisibility(View.VISIBLE);
                t.setDrawn(UiState.activityVisible);
            } else {
                t.park();
            }
        }
    }

    private void refreshUi() {
        if (mTabs == null) return;
        BrowserTab cur = currentTab();
        // Re-assert which WebView is shown (a tab rebuilt after a renderer crash, a new tab...).
        layoutTabs(cur);
        refreshChips();
        if (cur != null && cur.isWeb() && !mInPip) {
            mTabHeader.setVisibility(View.VISIBLE);
            mTabTitle.setText(cur.displayTitle());
            String url = cur.currentUrl() != null ? cur.currentUrl() : cur.web.url;
            mTabHost.setText(mAccount.name + "  \u00b7  " + UrlRouter.host(url));
        } else {
            mTabHeader.setVisibility(View.GONE);
        }
        boolean waiting = cur != null && (mPendingAttach != null || mPendingText != null) && cur.key.equals(mPendingAttachKey);
        boolean composing = cur != null && (cur.composeFocused() || waiting);
        mBtnAttach.setVisibility(composing && !mInPip ? View.VISIBLE : View.GONE);
        // The user just tapped a message box: deliver what was waiting for one.
        if (waiting && cur.composeFocused() && cur.composeChangedAt() > mPendingSince) deliverPending(cur);
        boolean popupShown = mPopupPanel.getVisibility() == View.VISIBLE;
        if (cur != null) {
            int p = cur.progress();
            mProgress.setVisibility(p < 100 && !popupShown ? View.VISIBLE : View.GONE);
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
        m.add(0, 12, 0, R.string.menu_attach);
        m.add(0, 11, 0, R.string.menu_new_tab);
        m.add(0, 9, 0, R.string.menu_accounts);
        m.add(0, 10, 0, R.string.menu_add_account);
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
                    case 9:
                        startActivity(new Intent(MainActivity.this, AccountsActivity.class));
                        return true;
                    case 10:
                        addAccount();
                        return true;
                    case 11:
                        if (mAccount != null) showNewTabDialog(mAccount);
                        return true;
                    case 12:
                        pickFilesToAttach();
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
        Account account = mAccounts.find(intent.getStringExtra(EXTRA_ACCOUNT));
        if (account == null) account = mAccount != null ? mAccount : mAccounts.active();
        final String notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID);
        if (ACTION_OPEN_SITE.equals(action) && site != null) {
            showTab(account, site);
            final BrowserTab t = mTabs.tab(account, site);
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
            showTab(account, Site.TEAMS);
        } else if (ACTION_ANSWER_CALL.equals(action)) {
            showOverLockScreen(true);
            Notifier.get(this).cancelCallAlert();
            showTab(account, Site.TEAMS);
            final BrowserTab t = mTabs.tab(account, Site.TEAMS);
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
        } else if (ACTION_ATTACH.equals(action)) {
            handleShared(intent, account, site != null ? site : account.preferredSite());
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            openExternalLink(intent.getData().toString());
        }
        // Consume one-shot actions so a rotation/recreate doesn't repeat them.
        intent.setAction(Intent.ACTION_MAIN);
    }

    /** A Teams/Outlook/mailto link opened from another app: opens in the account on screen. */
    private void openExternalLink(String url) {
        Account a = mAccount != null ? mAccount : mAccounts.active();
        if (url.startsWith("msteams:")) url = UrlRouter.msteamsToHttps(url);
        if (url.startsWith("mailto:")) {
            if (a.siteEnabled(Site.OUTLOOK)) {
                openIn(a, Site.OUTLOOK, UrlRouter.mailtoToOutlook(url, a.homeUrl(Site.OUTLOOK)));
            }
            return;
        }
        if (!UrlRouter.isHttp(url)) return;
        Site target = UrlRouter.siteFor(url);
        if (target != null && a.siteEnabled(target)) openIn(a, target, url);
        else openNewTab(a, url, currentTab());
    }

    private void openIn(Account a, Site site, String url) {
        BrowserTab t = mTabs.tab(a, site);
        if (t == null) return;
        showTab(a, site);
        if (url != null) t.loadUrl(url);
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
        // Volume keys control the call's volume during a call (not media volume).
        setVolumeControlStream(inCall ? android.media.AudioManager.STREAM_VOICE_CALL
                : android.media.AudioManager.USE_DEFAULT_STREAM_TYPE);
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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Dark/light theme switched: rebuild the screen (the WebViews themselves survive).
        if ((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) != mNightMode && mTabs != null && !mInPip) {
            mNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            recreate();
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
        WebView topPopup = currentPopup();
        if (topPopup != null) {
            WebView top = topPopup;
            if (top.canGoBack()) top.goBack();
            else closePopup(top);
            return;
        }
        if (mZoomBar.getVisibility() == View.VISIBLE) {
            mZoomBar.setVisibility(View.GONE);
            return;
        }
        BrowserTab t = currentTab();
        if (t != null && t.isWeb() && !t.canGoBack()) {
            // First page of a link tab: back to Teams/Outlook (the tab stays in the drop-up).
            returnFromWebTab(mAccount, mOpenedFrom.get(t.key));
            return;
        }
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
            // Any file the page accepts, from any app (Files, Photos, Drive...), several at once.
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            String[] mimes = mimeTypesFor(params.getAcceptTypes());
            if (mimes.length > 0) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
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

    /** "image/*,.pdf" -> {"image/*", "application/pdf"}; empty means anything. */
    private static String[] mimeTypesFor(String[] accept) {
        List<String> out = new ArrayList<>();
        if (accept == null) return new String[0];
        for (String entry : accept) {
            if (entry == null) continue;
            for (String part : entry.split(",")) {
                String a = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (a.isEmpty()) continue;
                if (a.equals("*/*") || a.equals("*")) return new String[0];
                if (a.contains("/")) {
                    if (!out.contains(a)) out.add(a);
                } else if (a.startsWith(".")) {
                    String m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.substring(1));
                    if (m != null && !out.contains(m)) out.add(m);
                    else if (m == null) return new String[0]; // unknown extension: don't hide files
                }
            }
        }
        return out.toArray(new String[0]);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH) {
            if (resultCode != RESULT_OK) return;
            BrowserTab t = mAttachTargetKey == null ? null : mTabs.existing(mAttachTargetKey);
            if (t == null) t = currentTab();
            List<com.workdeck.app.web.Attachments.Item> items = new ArrayList<>();
            for (Uri u : urisOf(data)) items.add(com.workdeck.app.web.Attachments.add(this, u));
            attachTo(t, items);
            return;
        }
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
        if (mPopups.size() >= 6) return false;
        final WebView popup = opener.createPopupWebView(this);
        mPopups.add(popup);
        mPopupOwner.put(popup, opener.key);
        popup.setVisibility(View.INVISIBLE);
        mPopupContainer.addView(popup);
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        // Script-filled windows (e.g. Outlook "pop out") never navigate anywhere:
        // show them if no navigation decided their fate within a moment.
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mPopups.contains(popup) && popup.getVisibility() == View.INVISIBLE) {
                    String url = popup.getUrl();
                    if (url == null || url.isEmpty() || url.startsWith("about:")) showPopup(popup);
                }
            }
        }, 1500);
        return true;
    }

    /** The top popup window of the tab on screen, or null. INVISIBLE = not shown yet. */
    private WebView currentPopup() {
        String key = currentKey();
        for (int i = mPopups.size() - 1; i >= 0; i--) {
            WebView p = mPopups.get(i);
            if (p.getVisibility() != View.INVISIBLE && key != null && key.equals(mPopupOwner.get(p))) return p;
        }
        return null;
    }

    /** Shows the popup panel only over the tab that opened the popup. */
    private void updatePopupPanel() {
        WebView top = currentPopup();
        for (WebView p : mPopups) {
            if (p.getVisibility() != View.INVISIBLE) p.setVisibility(p == top ? View.VISIBLE : View.GONE);
        }
        if (top == null) {
            mPopupPanel.setVisibility(View.GONE);
        } else {
            String title = top.getTitle();
            if (title == null || title.isEmpty()) title = UrlRouter.host(top.getUrl() == null ? "" : top.getUrl());
            mPopupTitle.setText(title);
            mPopupPanel.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void showPopup(WebView popup) {
        if (!mPopups.contains(popup)) return;
        popup.setVisibility(View.GONE); // decided: it is shown (on top when its tab is on screen)
        mPopupTitle.setText(UrlRouter.host(popup.getUrl() == null ? "" : popup.getUrl()));
        updatePopupPanel();
        refreshUi();
    }

    @Override
    public void onPopupTitle(WebView popup, String title) {
        if (popup == currentPopup() && title != null) mPopupTitle.setText(title);
    }

    @Override
    public void closePopup(final WebView popup) {
        if (!mPopups.remove(popup)) return;
        mPopupOwner.remove(popup);
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
        updatePopupPanel();
        refreshUi();
    }

    @Override
    public void switchToSite(BrowserTab from, Site site, String url) {
        openIn(from.account, site, url);
    }

    private static boolean sOutdatedShown;

    @Override
    public void onBrowserOutdated(String chromeMajor) {
        if (sOutdatedShown || isFinishing()) return;
        sOutdatedShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.outdated_title)
                .setMessage(getString(R.string.outdated_text, chromeMajor))
                .setPositiveButton(R.string.outdated_update, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openStore("com.google.android.webview");
                    }
                })
                .setNeutralButton(R.string.outdated_chrome, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openStore("com.android.chrome");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openStore(String pkg) {
        AccountDialogs.openStore(this, pkg);
    }
}
