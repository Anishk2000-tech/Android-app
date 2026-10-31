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

    void switchToSite(Site site, String url);

    boolean isSiteVisible(Site site);
}
