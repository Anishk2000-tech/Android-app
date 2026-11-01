package com.workdeck.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.workdeck.app.Account;
import com.workdeck.app.Accounts;
import com.workdeck.app.Prefs;
import com.workdeck.app.R;
import com.workdeck.app.Site;
import com.workdeck.app.web.Attachments;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Share" target: files (photos, videos, PDFs, anything) or text shared from another
 * app go into the message box of the chosen account's Teams or Outlook.
 */
public final class ShareActivity extends Activity {
    private static final String TAG = "WorkDeck/Share";

    private final List<Uri> mUris = new ArrayList<>();
    private String mText;
    private String mType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        mType = in.getType();
        readShared(in);
        if (mUris.isEmpty() && (mText == null || mText.isEmpty())) {
            finish();
            return;
        }
        if (!Prefs.get(this).setupDone()) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        chooseDestination();
    }

    @SuppressWarnings("deprecation")
    private void readShared(Intent in) {
        String action = in.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) mUris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) mUris.add(u);
        }
        if (mUris.isEmpty() && in.getClipData() != null) {
            for (int i = 0; i < in.getClipData().getItemCount(); i++) {
                Uri u = in.getClipData().getItemAt(i).getUri();
                if (u != null) mUris.add(u);
            }
        }
        CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t != null) mText = t.toString();
    }

    private static final class Target {
        final Account account;
        final Site site;

        Target(Account a, Site s) {
            account = a;
            site = s;
        }
    }

    private void chooseDestination() {
        Accounts accounts = Accounts.get(this);
        Account active = accounts.active();
        final List<Target> targets = new ArrayList<>();
        // The account/app in use comes first.
        if (active.siteEnabled(active.preferredSite())) targets.add(new Target(active, active.preferredSite()));
        for (Account a : accounts.list()) {
            for (Site s : Site.values()) {
                if (!a.siteEnabled(s)) continue;
                if (a == active && s == active.preferredSite()) continue;
                targets.add(new Target(a, s));
            }
        }
        if (targets.size() == 1) {
            send(targets.get(0));
            return;
        }
        String[] labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = targets.get(i).account.name + " · " + getString(targets.get(i).site.titleRes);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.share_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        send(targets.get(which));
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        finish();
                    }
                }).show();
    }

    /** Copies the files (the sharing app's permission is temporary), then hands over. */
    private void send(final Target t) {
        final AlertDialog progress = new AlertDialog.Builder(this).setMessage(R.string.share_preparing)
                .setCancelable(false).show();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Attachments.cleanCache(ShareActivity.this);
                final List<String> paths = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                final List<String> mimes = new ArrayList<>();
                boolean failed = false;
                for (Uri u : mUris) {
                    try {
                        String name = Attachments.displayName(ShareActivity.this, u);
                        File f = Attachments.copyToCache(ShareActivity.this, u, name);
                        String mime = null;
                        try {
                            mime = getContentResolver().getType(u);
                        } catch (Throwable ignored) {
                        }
                        if (mime == null && mType != null && !mType.contains("*")) mime = mType;
                        paths.add(f.getAbsolutePath());
                        names.add(name);
                        mimes.add(mime);
                    } catch (Throwable e) {
                        Log.w(TAG, "could not copy " + u, e);
                        failed = true;
                    }
                }
                final boolean anyFailed = failed;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            progress.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (anyFailed) Toast.makeText(ShareActivity.this, R.string.share_failed, Toast.LENGTH_LONG).show();
                        if (!paths.isEmpty() || (mText != null && !mText.isEmpty())) {
                            startActivity(new Intent(ShareActivity.this, MainActivity.class)
                                    .setAction(MainActivity.ACTION_ATTACH)
                                    .putExtra(MainActivity.EXTRA_ACCOUNT, t.account.id)
                                    .putExtra(MainActivity.EXTRA_SITE, t.site.key)
                                    .putExtra(MainActivity.EXTRA_FILES, paths.toArray(new String[0]))
                                    .putExtra(MainActivity.EXTRA_NAMES, names.toArray(new String[0]))
                                    .putExtra(MainActivity.EXTRA_MIMES, mimes.toArray(new String[0]))
                                    .putExtra(MainActivity.EXTRA_TEXT, mText)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        }
                        finish();
                    }
                });
            }
        }, "workdeck-share").start();
    }
}
