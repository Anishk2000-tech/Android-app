package com.workdeck.app.web;

import android.app.Activity;
import android.os.Message;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.workdeck.app.Site;

/** UI callbacks a tab needs while an activity is showing it (implemented by MainActivity). */
public interface TabHost {
    Activity activity();

    void onTabStateChanged(BrowserTab tab);

    /** Ask for Android runtime permissions, then run {@code done} (granted or not). */
    void requestRuntimePermissions(String[] permissions, Runnable done);

    boolean showFileChooser(ValueCallback<android.net.Uri[]> callback, WebChromeClient.FileChooserParams params);

    void showCustomView(View view, WebChromeClient.CustomViewCallback callback);

    void hideCustomView();

    boolean openPopup(BrowserTab opener, Message resultMsg, boolean userGesture);

    /** The popup's first page is a Microsoft page: show it in the in-app overlay. */
    void showPopup(WebView popup);

    void onPopupTitle(WebView popup, String title);

    void closePopup(WebView popup);

    /** Show another site of the same account as {@code from} (e.g. a Teams link in Outlook). */
    void switchToSite(BrowserTab from, Site site, String url);

    /** Open a link from {@code from} in a new tab of the same account (and show it). */
    void openLink(BrowserTab from, String url);

    /** The page took (or could not take) files handed to it with {@link BrowserTab#attach}. */
    void onAttachResult(BrowserTab tab, String method, int count, boolean seen);

    /**
     * The company's sign-in policy blocked the tab. {@code switched}: WorkDeck now presents
     * a computer browser there and reloaded it; otherwise the policy needs the managed Edge app.
     */
    void onAccessBlocked(BrowserTab tab, boolean switched);

    /** A site reported that the WebView engine is too old. */
    void onBrowserOutdated(String chromeMajor);
}
