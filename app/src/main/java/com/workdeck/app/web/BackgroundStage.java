package com.workdeck.app.web;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * An invisible window on a private virtual display that only WorkDeck can see. Pages
 * that aren't on the phone's screen (other accounts/apps, and all of them once WorkDeck
 * is in the background or swiped away) are attached here: the browser engine only keeps
 * pages running at full speed while they're attached to a window it considers visible -
 * a detached page is treated as hidden and slowed down until messages arrive late.
 * Nothing here is ever shown or (being placed off the window) even drawn.
 */
final class BackgroundStage {
    private static final String TAG = "WorkDeck/Stage";
    private static BackgroundStage sInstance;

    private final Context mApp;
    private VirtualDisplay mDisplay;
    private ImageReader mReader;
    /** Frees the window's frames - never on the main thread (see create()). */
    private HandlerThread mFrames;
    private FrameLayout mRoot;
    private boolean mFailed;

    static synchronized BackgroundStage get(Context c) {
        if (sInstance == null) sInstance = new BackgroundStage(c.getApplicationContext());
        return sInstance;
    }

    private BackgroundStage(Context app) {
        mApp = app;
    }

    /** The stage's container, or null if this phone doesn't allow it (then pages are parked elsewhere). */
    ViewGroup container() {
        if (mRoot != null) return mRoot;
        if (mFailed) return null;
        try {
            create();
            Log.i(TAG, "background stage ready");
            return mRoot;
        } catch (Throwable t) {
            Log.w(TAG, "background stage unavailable", t);
            mFailed = true;
            release();
            return null;
        }
    }

    private void create() {
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        int w = Math.max(1, dm.widthPixels);
        int h = Math.max(1, dm.heightPixels);
        // The window's frames must be freed on a thread of their own. The app's render thread
        // draws this window and the screen's; if no frame buffer is free it waits, and the
        // main thread waits for the render thread whenever it draws the screen - so freeing
        // the buffers on the main thread could deadlock both ("isn't responding").
        mFrames = new HandlerThread("workdeck-stage-frames");
        mFrames.start();
        Handler frames = new Handler(mFrames.getLooper());
        mReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        mReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                try {
                    Image i = r.acquireLatestImage();
                    if (i != null) i.close();
                } catch (Throwable ignored) {
                }
            }
        }, frames);
        DisplayManager dmgr = (DisplayManager) mApp.getSystemService(Context.DISPLAY_SERVICE);
        // Flags 0 = a private display owned by WorkDeck (no permission needed, nothing shown).
        mDisplay = dmgr.createVirtualDisplay("workdeck-background", w, h, dm.densityDpi, mReader.getSurface(), 0);
        if (mDisplay == null) throw new IllegalStateException("no virtual display");
        Display d = mDisplay.getDisplay();
        int type = WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
        Context wc = Build.VERSION.SDK_INT >= 30 ? mApp.createWindowContext(d, type, null) : mApp.createDisplayContext(d);
        WindowManager wm = (WindowManager) wc.getSystemService(Context.WINDOW_SERVICE);
        FrameLayout root = new FrameLayout(wc);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("WorkDeck background");
        wm.addView(root, lp);
        mRoot = root;
    }

    private void release() {
        try {
            if (mDisplay != null) mDisplay.release();
        } catch (Throwable ignored) {
        }
        try {
            if (mReader != null) mReader.close();
        } catch (Throwable ignored) {
        }
        if (mFrames != null) mFrames.quitSafely();
        mFrames = null;
        mDisplay = null;
        mReader = null;
        mRoot = null;
    }
}
