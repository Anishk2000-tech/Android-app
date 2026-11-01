# WorkDeck — Teams + Outlook on Android, without the official apps

WorkDeck is a small Android app (≈125 KB) that runs **Microsoft Teams** and **Outlook** on the web inside the phone's own Chrome engine (Android System WebView). It is built to do the things the "desktop browser" apps can't:

| Feature | How WorkDeck does it |
|---|---|
| **Stay signed in** | Your Microsoft sign-in (cookies + storage) is never cleared — not when you close the app, swipe it from Recents, or restart the phone. Only a password change, a Microsoft-side sign-out, or *Settings → Sign out & clear all data* ends it. |
| **Teams calls with mic & speaker** | Grants the microphone/camera to Teams' WebRTC calling (the reason other apps fail). During a call a **speaker button** switches between **Speaker, Phone earpiece, Bluetooth, Headset**. Bluetooth/wired headsets are used automatically when connected. |
| **Background notifications** | A foreground service keeps Teams & Outlook connected. Web notifications are turned into real Android notifications (with sound, lock-screen, tap-to-open). Incoming calls ring and show a **full-screen call alert** with *Answer / Decline*. |
| **Stays in memory** | Teams & Outlook keep running even after the app is closed or swiped away from Recents, and start again after a reboot. |
| **Desktop ⟷ mobile view** | One tap per app. Desktop view looks like Chrome on a Windows PC. |
| **Zoom in / out** | Pinch-zoom always works, plus zoom buttons, "Fit", text size, and desktop page width. |
| **Simple setup** | One setup screen with an **"Allow everything"** button. |
| Also | Unread badges, file downloads & uploads, meeting links from other apps open in WorkDeck, `mailto:` links open Outlook compose, picture-in-picture during calls, dark mode, auto-recovery if Android kills the page, Outlook ↔ Teams links open in the right tab. |

> WorkDeck is an independent, open-source app. It is **not made by or affiliated with Microsoft**. Teams and Outlook are trademarks of Microsoft Corporation. WorkDeck only shows Microsoft's own websites; it has no ads, no analytics and sends your data nowhere else.

---

## What's new in 1.1.0
* **Teams now detects the phone's microphone, camera and speaker.** Android WebView tells websites that microphone/camera are "blocked" and hides all device names, so Teams thought there were none. WorkDeck now answers from Android's real permissions and gives Teams a proper device list: *Phone microphone*, *Front/Back camera*, and speakers *Phone speaker (loud)*, *Phone earpiece*, *Bluetooth*, *Headset*. Choosing a speaker in Teams' device settings switches Android's call audio.
* **Message notifications even when Teams doesn't send one.** When the unread count goes up while you're not looking, WorkDeck posts a notification itself (with the sender and message preview when it can read them). If Teams does send its own notification, that one is used instead — no duplicates.
* **Incoming calls ring even without a Teams notification:** WorkDeck notices Teams' incoming-call screen (Accept/Decline) in the background and rings, and stops when the call is answered elsewhere or missed.
* Install it over 1.0.0 — you stay signed in.

## 1. Install (step by step)

1. **Download the APK** on your phone:
   open **[release/WorkDeck-1.1.0.apk](release/WorkDeck-1.1.0.apk)** on GitHub and tap **Download** (or use the APK file you were sent).
2. Open the downloaded file (tap the download notification, or open *Files → Downloads*).
3. If Android says *"For your security, your phone is not allowed to install unknown apps from this source"*: tap **Settings → turn on "Allow from this source"**, then go back.
4. Tap **Install**. If **Play Protect** warns about an unknown app, tap **More details → Install anyway** (it warns about every app not from the Play Store).
5. Tap **Open**.

Requirements: Android 8.0 or newer, and an up-to-date **Android System WebView** or **Google Chrome** from the Play Store (update them if Teams says the browser is not supported).

## 2. First-time setup (1 minute)

The setup screen opens automatically:

1. **Your Microsoft account** — choose *Work or school (Microsoft 365)* or *Personal (Outlook.com / Hotmail / Teams free)*.
2. **What do you want to use?** — Teams, Outlook, or both.
3. **Permissions** — tap **Allow everything** and accept every Android prompt:
   * **Microphone** (needed for calls), **Camera** (video), **Notifications**, **Nearby devices** (Bluetooth headsets),
   * then **"Let app always run in background?" → Allow** (battery).
   * On Android 14+, tap **Open** next to *Incoming-call screen* and switch it **on** (lets calls appear on the lock screen).
   * On Xiaomi / Redmi / POCO / Oppo / Realme / Vivo / OnePlus / Samsung / Huawei / Honor phones also tap **Open** next to *Phone-maker battery settings* and allow **Autostart** / set battery to **No restrictions / Unrestricted**. Then open Recents and **lock** WorkDeck (padlock icon) if your phone has that.
4. Tap **Start WorkDeck**.

## 3. Sign in

1. The **Teams** tab shows the Microsoft sign-in page. Enter your email and password (and your company's sign-in / MFA if asked).
2. When Microsoft asks **"Stay signed in?" → tap Yes** (tick "Don't show this again"). This is what keeps you signed in permanently.
3. Tap the **Outlook** tab. It normally signs in automatically with the same account (otherwise sign in once the same way).

### Turn on notifications inside Teams / Outlook (once)
* **Teams**: notifications are on by default. To check: profile picture → **Settings → Notifications and activity**.
* **Outlook**: **Settings (gear) → General → Notifications** → turn on **desktop notifications** for new mail (and calendar reminders).

## 4. Using WorkDeck

The bottom bar:

| Button | What it does |
|---|---|
| **Teams** / **Outlook** | Switch app. Red badge = unread count. |
| 🔊 **Speaker** (only during a call) | Tap: cycle Speaker → Earpiece → Bluetooth. Long-press: pick from a list. |
| 🖥 / 📱 | Switch **desktop ⟷ mobile view** for the current app. |
| 🔍 **Zoom** | Shows the zoom bar: **−  Fit  +**, text size **A− / A+**, and the desktop page **Width** (smaller width = bigger text). Pinch-zoom works too. |
| ⋮ **Menu** | Reload, start page, hide toolbar (full screen), open in browser, Setup & permissions, Settings, **Quit**. |

* The **back** button goes back inside Teams/Outlook; at the start it just goes to the home screen and WorkDeck **keeps running**.
* The notification **"WorkDeck is connected"** must stay (Android requires it for background apps). You can long-press it → turn the "Running in background" category to *silent/minimized*. Its **Quit** button fully stops WorkDeck (you stay signed in).

### Calls
* **Incoming call:** the phone rings and shows *Incoming Teams call* (full-screen on the lock screen). Tap **Answer** (WorkDeck opens Teams and presses Accept for you — if not, tap Teams' green **Accept** button) or tap **Decline**.
* **During a call:** use the speaker button (bottom bar or the "Teams call in progress" notification) to switch **Speaker / Earpiece / Bluetooth**. Holding the phone to your ear with earpiece selected turns the screen off. The screen stays on otherwise.
* **Leaving the app during a call** shows it in a small picture-in-picture window; the call (and your microphone) keeps working.
* **Bluetooth earbuds / car:** connect them before or during the call — WorkDeck switches to them automatically.
* **In Teams' own device settings** (in a call: *More → Settings → Device settings*), you can also pick *Phone speaker (loud)*, *Phone earpiece* or *Bluetooth* as the speaker, and *Front/Back camera*.
* **Meeting links** from mail or other apps: choose **WorkDeck** when Android asks which app to open them with.

### Settings (⋮ → Settings)
Account type, enable Teams/Outlook, custom start pages (special Microsoft clouds), default view per app, desktop width, *Keep running in background*, *Reliable mode* (keeps the processor awake for instant notifications — uses more battery, on by default), *Start after restart*, per-app notifications, call alerts/ringing, default call audio, picture-in-picture, **Sign out & clear all data**.

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| No notifications after a while | ⋮ → **Setup & permissions**: everything should say *Allowed ✓*. Do the phone-maker step (Autostart, battery *Unrestricted*, lock in Recents). See [dontkillmyapp.com](https://dontkillmyapp.com) for your phone. Keep *Reliable mode* on. |
| Outlook mail notifications missing | Turn on desktop notifications in Outlook settings (section 3). |
| Call: others can't hear me | Setup must show *Microphone: Allowed ✓*. In the Teams call, open **More → Settings → Device settings** and pick the default microphone. |
| No sound / wrong speaker | Tap the speaker button in the bottom bar during the call. |
| "Your browser version isn't supported" / blank page | WorkDeck shows a prompt with **Update WebView / Update Chrome** buttons when Microsoft reports this. Update **Android System WebView** and **Google Chrome** in the Play Store, then ⋮ → Reload. (Teams only supports the latest few Chrome versions.) |
| Sign-in loops / wrong account | ⋮ → Settings → **Sign out & clear all data**, then sign in again. |
| Company says device not allowed | Your organisation's policy (e.g. Intune / Conditional Access) may only allow approved apps. WorkDeck can't bypass that. |
| Text too small in desktop view | Zoom button → choose a smaller **Width** (e.g. 800px) or use **A+**. Or switch to mobile view. |

## 6. Honest limitations

* **Screen sharing from the phone** is not possible: Android WebView does not support sharing your screen (`getDisplayMedia`). You can still *see* others' shared screens.
* Background blur / video effects may be unavailable.
* Notifications come from the Teams/Outlook web pages staying connected in the background (Microsoft has no push service for third-party apps). That's why the background service and battery settings matter. Phones that force-kill apps can still delay them.
* Answering is done in Teams' own call screen; the *Answer* button opens it and tries to press Accept automatically.
* Microsoft can change their websites at any time; WorkDeck uses the real web apps, so it follows those changes.

## 7. What has been tested (and what hasn't)

**Automated, on real Android (emulators in GitHub Actions — Android 14 and Android 12, the real Android System WebView):**
setup screen; both tabs load; keep-alive foreground service (with microphone + camera service types); Teams presents as desktop Chrome on Windows (user-agent, `navigator.platform`, client hints); the integration script is injected into Microsoft pages; the Teams service worker gets the notification shim; a web notification becomes an Android notification on the *Teams messages* channel; an incoming-call notification becomes a ringing call alert; WebRTC `getUserMedia` gets a live microphone track; the call is detected ("Teams call in progress" with speaker/earpiece buttons) and stays active with the app in the background; zoom buttons; the app stays alive 60 s in the background and after being swiped away from Recents, and the **same Teams page is still in memory** afterwards (no reload); the delivered release APK installs, runs and keeps its service. (These device tests found and fixed a real crash in the zoom *Fit* button.)

**Automated in Chromium:** 19 tests of the page-integration script (notifications incl. clicks & service workers, identity, viewport/zoom, badges, mic tracking, blob downloads, call buttons, token isolation) and 34 checks of the URL-routing / call-detection logic.

**Not tested (needs your phone and your Microsoft account):** signing in with a real account, receiving real Teams/Outlook messages, and a real two-person Teams call with sound on speaker/Bluetooth. Please try a short test call with a colleague first.

## 8. Privacy & security

* Everything stays on your phone. WorkDeck talks only to Microsoft's sites (and opens other links in your normal browser).
* No ads, no analytics, no trackers, no backups of your sign-in (Android backup is disabled).
* The page↔app bridge only accepts calls carrying a secret token that is injected **only** into Microsoft's own app & sign-in domains, so other websites (e.g. ads) can't post fake notifications or save files.

## 9. Updating

Install a newer WorkDeck APK over the old one — **you stay signed in**. This only works if the new APK is signed with the **same signing key** as the installed one; otherwise Android refuses and you'd have to uninstall (and sign in again). Keep the signing key (`workdeck-release.p12` + password) safe — see below.

---

## 10. For developers: build from source

### Option A — Android Studio (easiest)
1. Install [Android Studio](https://developer.android.com/studio).
2. *File → Open* → select this folder. Let it sync (it downloads Gradle and the Android SDK).
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The APK is in `app/build/outputs/apk/`.

### Option B — command line
```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed with your key if configured (below), else the debug key
```

### Signing with your release key
Create `keystore.properties` in the project root (it is git-ignored — never commit it):
```properties
storeFile=/full/path/to/workdeck-release.p12
storePassword=YOUR_PASSWORD
keyAlias=workdeck
```
To sign updates that install over the provided APK, use the **same** `workdeck-release.p12`.

### GitHub Actions (automatic builds + real Android test)
Every push runs `.github/workflows/android.yml`:
1. builds debug + release APKs with Gradle and runs the logic tests;
2. boots an **Android 14 emulator** and runs `scripts/ci/emulator-test.sh`: setup flow, foreground service, the real Android WebView (identity, injected script, notification → Android notification, call alert, microphone/WebRTC + call detection in background, zoom, desktop/mobile switch), background survival, Recents removal, crash check — then smoke-tests `release/*.apk`.
APKs and screenshots are attached to each run (*Actions → run → Artifacts*).
To have CI sign release builds with your key, add repository secrets `WORKDECK_KEYSTORE_B64` (`base64 -w0 workdeck-release.p12`) and `WORKDECK_KS_PASS`.

### Option C — without Gradle
`scripts/build-offline.sh` builds and signs the APK using only `aapt2`, `javac`, D8 and apksig (see the script header for the inputs). `scripts/run-tests.sh` runs the JVM tests and the Chromium tests of the injected script (`tests/js/inject.test.js`, needs Node + Playwright).

### How it works (code map)
```
app/src/main/java/com/workdeck/app/
  web/BrowserTab.java      one long-lived WebView per site: settings, navigation policy, popups,
                           WebRTC permission grants, downloads, renderer-crash recovery
  web/TabManager.java      process-wide owner of the tabs (outlives activities)
  web/UserAgents.java      desktop Chrome/Windows + Chrome-for-Android identities from the real engine version
  web/WebViewSupport.java  document-start scripts + UA client hints via the WebView APK's support-library glue
  web/JsBridge.java        token-protected window.WorkDeckBridge used by the injected script
  web/SwShim.java          adds a notification shim to Microsoft service-worker scripts
  web/UrlRouter.java       which URLs are Teams / Outlook / Microsoft / external (pure Java, unit-tested)
  call/CallManager.java    call detection + speaker/earpiece/Bluetooth/wired routing, wake locks
  notify/Notifier.java     Android notifications, incoming-call alerts, downloads
  service/KeepAliveService foreground service (special-use + microphone/camera types), boot restart
  ui/MainActivity.java     tabs, bottom bar, zoom bar, popups, PiP, full-screen video
  ui/SetupActivity.java    first-run setup & permissions; ui/SettingsActivity.java settings
app/src/main/assets/workdeck/
  inject.js                document-start script: Notification API, desktop identity, viewport/zoom,
                           Badging API, blob downloads, mic tracking, call buttons
  sw-shim.js               forwards service-worker notifications to the page
```

### Why the two reference apps can't take calls
* **Desktop Browser (com.computer.shortkeyszz)** — a Flutter app (flutter_inappwebview) that only changes the user-agent. Its manifest has **no `RECORD_AUDIO`, `CAMERA`, `MODIFY_AUDIO_SETTINGS`, Bluetooth or `POST_NOTIFICATIONS`** permission, so Teams can never get the microphone and nothing can notify you.
* **FoxBox (tech.ula.foxbox_pro)** — runs a Linux distribution (proot, UserLAnd-based) and shows desktop Firefox through a VNC viewer. It also has **no `RECORD_AUDIO`**, and audio from a proot/VNC desktop doesn't reach Android's microphone/speaker routing, so calls can't work.

WorkDeck instead uses Android's native Chromium WebView (same engine as Chrome), grants WebRTC access properly, and handles Android audio routing, background execution and notifications natively.

## License
MIT. The tiny interfaces in `org/chromium/support_lib_boundary` mirror Chromium's public WebView support-library boundary (BSD-licensed).
