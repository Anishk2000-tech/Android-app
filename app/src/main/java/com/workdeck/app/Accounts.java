package com.workdeck.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.workdeck.app.web.WebViewSupport;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The list of accounts, which one is active, and their settings (persisted as JSON). */
public final class Accounts {
    private static final String TAG = "WorkDeck/Accounts";
    private static final String KEY_LIST = "accounts_json";
    private static final String KEY_ACTIVE = "active_account";
    private static final String KEY_NEXT = "next_account_id";
    /** Accounts created on first run (the first one keeps any existing sign-in). */
    static final String[] SEED_NAMES = {"CRAFTWW", "OMC", "ACXIOM"};

    private static Accounts sInstance;

    private final SharedPreferences mSp;
    private final List<Account> mList = new ArrayList<>();
    private String mActive;

    public static synchronized Accounts get(Context c) {
        if (sInstance == null) sInstance = new Accounts(c.getApplicationContext());
        return sInstance;
    }

    private final Context mApp;

    private Accounts(Context app) {
        mApp = app;
        mSp = app.getSharedPreferences("workdeck", Context.MODE_PRIVATE);
        load(Prefs.get(app));
    }

    private void load(Prefs legacy) {
        String json = mSp.getString(KEY_LIST, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) mList.add(Account.fromJson(arr.getJSONObject(i)));
            } catch (Throwable t) {
                Log.e(TAG, "corrupt account list, starting over", t);
                mList.clear();
            }
        }
        if (mList.isEmpty()) {
            if (Edition.solo(mApp)) seedSolo();
            else migrate(legacy);
        }
        mActive = mSp.getString(KEY_ACTIVE, mList.get(0).id);
        if (find(mActive) == null) mActive = mList.get(0).id;
    }

    /**
     * A single-app build: exactly one account with exactly one app, in mobile view. It is
     * its own installed app (its own storage), so it uses the WebView's default profile.
     */
    private void seedSolo() {
        Site only = Edition.site(mApp);
        Account a = new Account("a1", WebViewSupport.DEFAULT_PROFILE, Edition.account(mApp), 0);
        a.type = Prefs.ACCOUNT_WORK;
        a.lastSite = only;
        for (Site s : Site.values()) {
            a.setSiteEnabled(s, s == only);
            a.setDesktop(s, false);
        }
        // Teams always presents a computer browser (it refuses phone browsers); Outlook
        // too where the company is known to block phone browsers - the layout stays mobile.
        if (Edition.computerIdentity(mApp)) a.setComputerIdentity(only, true);
        a.markOpened(only);
        mList.add(a);
        mSp.edit().putInt(KEY_NEXT, 2).apply();
        save();
        Log.i(TAG, "single-app edition: " + a.name + " " + only.key);
    }

    /** Version 1.x had a single account: it becomes the first account, on the default profile. */
    private void migrate(Prefs legacy) {
        for (int i = 0; i < SEED_NAMES.length; i++) {
            String id = "a" + (i + 1);
            String profile = i == 0 ? WebViewSupport.DEFAULT_PROFILE : "wd_" + id;
            Account a = new Account(id, profile, SEED_NAMES[i], i);
            if (i == 0) {
                a.type = legacy.accountType();
                Site last = Site.fromKey(legacy.lastSite());
                a.lastSite = last == null ? Site.TEAMS : last;
                for (Site s : Site.values()) {
                    a.setSiteEnabled(s, legacy.siteEnabled(s));
                    a.setDesktop(s, legacy.isDesktop(s));
                    a.setTextZoom(s, legacy.textZoom(s));
                    a.setNotificationsEnabled(s, legacy.notificationsEnabled(s));
                    a.setCustomUrl(s, legacy.customUrl(s));
                    // Already in use before the upgrade: keep running it in the background.
                    if (legacy.setupDone() && legacy.siteEnabled(s)) a.markOpened(s);
                }
            }
            mList.add(a);
        }
        mSp.edit().putInt(KEY_NEXT, SEED_NAMES.length + 1).apply();
        save();
        Log.i(TAG, "created accounts " + SEED_NAMES.length);
    }

    public synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : mList) arr.put(a.toJson());
            mSp.edit().putString(KEY_LIST, arr.toString()).putString(KEY_ACTIVE, mActive).apply();
        } catch (Throwable t) {
            Log.e(TAG, "save failed", t);
        }
    }

    public synchronized List<Account> list() {
        return Collections.unmodifiableList(new ArrayList<>(mList));
    }

    public synchronized Account find(String id) {
        if (id == null) return null;
        for (Account a : mList) if (a.id.equals(id)) return a;
        return null;
    }

    public synchronized Account active() {
        Account a = find(mActive);
        return a != null ? a : mList.get(0);
    }

    public synchronized void setActive(Account a) {
        if (a == null || a.id.equals(mActive)) return;
        mActive = a.id;
        save();
    }

    public synchronized boolean multiple() {
        return mList.size() > 1;
    }

    /** A short signature of names/order/enabled apps, to know when the bar must be rebuilt. */
    public synchronized String signature() {
        StringBuilder sb = new StringBuilder();
        for (Account a : mList) {
            sb.append(a.id).append(':').append(a.name).append(':').append(a.color);
            for (Site s : Site.values()) sb.append(a.siteEnabled(s) ? '1' : '0');
            sb.append(';');
        }
        return sb.toString();
    }

    public synchronized Account add(String name, String type) {
        int next = mSp.getInt(KEY_NEXT, mList.size() + 1);
        String id = "a" + next;
        while (find(id) != null) id = "a" + (++next);
        Account a = new Account(id, "wd_" + id, name.trim().isEmpty() ? "Account " + next : name.trim(),
                mList.size());
        a.type = type;
        mList.add(a);
        mSp.edit().putInt(KEY_NEXT, next + 1).apply();
        save();
        return a;
    }

    public synchronized void remove(Account a) {
        if (mList.size() <= 1) return;
        mList.remove(a);
        if (a.id.equals(mActive)) mActive = mList.get(0).id;
        save();
    }

    public synchronized void move(Account a, int delta) {
        int i = mList.indexOf(a);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= mList.size()) return;
        Collections.swap(mList, i, j);
        save();
    }
}
