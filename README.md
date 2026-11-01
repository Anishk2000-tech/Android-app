# WorkDeck — Teams + Outlook on Android, without the official apps

WorkDeck is a small Android app (≈160 KB) that runs **Microsoft Teams** and **Outlook** on the web inside the phone's own Chrome engine (Android System WebView). It is built to do the things the "desktop browser" apps can't:

| Feature | How WorkDeck does it |
|---|---|
| **Stay signed in** | Your Microsoft sign-in (cookies + storage) is never cleared — not when you close the app, swipe it from Recents, or restart the phone. Only a password change, a Microsoft-side sign-out, or signing out in WorkDeck ends it. |
| **Several Microsoft accounts** | One drop-up per account in the bottom bar — **CRAFTWW**, **OMC**, **ACXIOM** to start with, and **+** to add more. Each account has its own Teams + Outlook, its own sign-in and its own settings, and they all notify at the same time. |
| **Teams calls with mic & speaker** | Grants the microphone/camera to Teams' WebRTC calling (the reason other apps fail). During a call a **speaker button** switches between **Speaker, Phone earpiece, Bluetooth, Headset**. Bluetooth/wired headsets are used automatically when connected. |
| **Background notifications** | A foreground service keeps Teams & Outlook connected. Web notifications are turned into real Android notifications (with sound, lock-screen, tap-to-open). Incoming calls ring and show a **full-screen call alert** with *Answer / Decline*. |
| **Stays in memory** | Teams & Outlook keep running even after the app is closed or swiped away from Recents, and start again after a reboot. |
| **Desktop ⟷ mobile view** | One tap per app. Desktop view looks like Chrome on a Windows PC. |
| **Zoom in / out** | Pinch-zoom always works, plus zoom buttons, "Fit", text size, and desktop page width. |
| **Simple setup** | One setup screen with an **"Allow everything"** button. |
| Also | Unread badges, file downloads & uploads, meeting links from other apps open in WorkDeck, `mailto:` links open Outlook compose, picture-in-picture during calls, dark mode, auto-recovery if Android kills the page, Outlook ↔ Teams links open in the right tab. |

> WorkDeck is an independent, open-source app. It is **not made by or affiliated with Microsoft**. Teams and Outlook are trademarks of Microsoft Corporation. WorkDeck only shows Microsoft's own websites; it has no ads, no analytics and sends your data nowhere else.

---

## What's new in 1.3.0
* **Send phone files in Teams and Outlook** — photos, videos, PDFs, documents, anything:
  * While you type in a chat or email, a **📎 button** appears in the bottom bar: tap it, pick one or more files, and they are added to the message (WorkDeck hands them to the message box the same way dragging files onto it does). Also in ⋮ → *Attach files from phone…*.
  * From any app (Gallery, Files, WhatsApp, Drive…): **Share → WorkDeck**, pick the account and Teams or Outlook, then tap the chat's message box if it isn't open yet. Shared text is typed into the message box.
  * Teams' and Outlook's own *Upload from this device* now show Android's full file picker (any file type, several at once).
* **Links open inside their account.** A link you tap in an account's Teams or Outlook opens as a **new tab of that same account** (signed in as that account), named after the page. It is listed in that account's drop-up with a **×** to close it — Teams and Outlook always stay. A slim bar on top of the page shows its name, a ×, reload and *open in the phone's browser*. **New tab…** in every drop-up (and in ⋮) opens any address, a search, or a quick link (Microsoft 365, OneDrive, SharePoint, calendar) in that account. Back on a link tab's first page returns to Teams/Outlook. Prefer the phone's browser? ⋮ → Settings → *Open links inside WorkDeck* off.
* **Choose the notification sound** for Teams messages and for Outlook mail (⋮ → Settings → *Notification sounds*): any of the phone's sounds, any sound file, the default, or silent.
* **Louder notifications: 300%** by default, adjustable from 100% to 400% (*Notification loudness*). WorkDeck plays the sound itself with Android's loudness boost. Silent/vibrate mode, Do Not Disturb and calls in progress still keep it quiet.
* **Bluetooth and headset microphones in Teams.** Teams' microphone list now has *Phone microphone*, *Bluetooth: <your headset>* and *Headset microphone* (plus *Automatic*). Picking one moves the call to that device — on Android a headset's microphone and speaker always go together.
* **Steadier call audio.** WorkDeck now owns the call's audio device: it switches to call audio as soon as Teams opens the microphone, keeps checking during the call and puts the sound back where you chose if anything else moves it, waits for a Bluetooth headset's call audio to come up (and falls back cleanly if it can't), follows headsets being connected/disconnected mid-call, and the **volume keys control the call volume** during a call.

## What's new in 1.2.0
* **Several Microsoft accounts at once.** The bottom bar now shows one button per account: **CRAFTWW**, **OMC** and **ACXIOM**. Tap one and a **drop-up** opens with that account's **Teams** and **Outlook** (with unread counts) and its settings. Tap **+** to add another account; rename, re-colour, re-order or delete accounts under ⋮ → **Manage accounts**.
* **Every account is completely separate:** its own Microsoft sign-in, cookies and storage (signing in or out of one never touches the others), and its **own settings** — work/personal, Teams and/or Outlook, desktop/mobile view, text size, notifications, *keep running*, custom start pages.
* **All accounts notify in the background.** Notifications say which account they belong to (e.g. *OMC · Teams*), and tapping one opens the right account. The red number on an account button is its total unread.
* **Outlook notifications fixed.** Outlook on the web shows no unread count in its title and only sends browser notifications when switched on in its own settings, so WorkDeck got nothing. WorkDeck now reads the **Inbox unread number** itself and notifies you about new mail (with the sender and subject), and removes the notification when the mail is read.
* **Updating from 1.1.0:** install over it — you stay signed in. Your existing sign-in becomes the **CRAFTWW** account (rename it if it's a different company). The other accounts need an up-to-date **Android System WebView** (Play Store); if it's too old, those accounts show an *update WebView* page instead of mixing up sign-ins.

## What's new in 1.1.0
* **Teams now detects the phone's microphone, camera and speaker.** Android WebView tells websites that microphone/camera are "blocked" and hides all device names, so Teams thought there were none. WorkDeck now answers from Android's real permissions and gives Teams a proper device list: *Phone microphone*, *Front/Back camera*, and speakers *Phone speaker (loud)*, *Phone earpiece*, *Bluetooth*, *Headset*. Choosing a speaker in Teams' device settings switches Android's call audio.
* **Message notifications even when Teams doesn't send one.** When the unread count goes up while you're not looking, WorkDeck posts a notification itself (with the sender and message preview when it can read them). If Teams does send its own notification, that one is used instead — no duplicates.
* **Incoming calls ring even without a Teams notification:** WorkDeck notices Teams' incoming-call screen (Accept/Decline) in the background and rings, and stops when the call is answered elsewhere or missed.
* Install it over 1.0.0 — you stay signed in.

## 1. Install (step by step)

1. **Download the APK** on your phone:
   open **[release/WorkDeck-1.3.0.apk](release/WorkDeck-1.3.0.apk)** on GitHub and tap **Download** (or use the APK file you were sent).
2. Open the downloaded file (tap the download notification, or open *Files → Downloads*).
3. If Android says *"For your security, your phone is not allowed to install unknown apps from this source"*: tap **Settings → turn on "Allow from this source"**, then go back.
4. Tap **Install**. If **Play Protect** warns about an unknown app, tap **More details → Install anyway** (it warns about every app not from the Play Store).
5. Tap **Open**.

Requirements: Android 8.0 or newer, and an up-to-date **Android System WebView** or **Google Chrome** from the Play Store (update them if Teams says the browser is not supported).

## 2. First-time setup (1 minute)

The setup screen opens automatically:

1. **Your Microsoft account** — choose *Work or school (Microsoft 365)* or *Personal (Outlook.com / Hotmail / Teams free)*. (On first setup this is used for all accounts; each account can be changed later.)
2. **What do you want to use?** — Teams, Outlook, or both.
3. **Permissions** — tap **Allow everything** and accept every Android prompt:
   * **Microphone** (needed for calls), **Camera** (video), **Notifications**, **Nearby devices** (Bluetooth headsets),
   * then **"Let app always run in background?" → Allow** (battery).
   * On Android 14+, tap **Open** next to *Incoming-call screen* and switch it **on** (lets calls appear on the lock screen).
   * On Xiaomi / Redmi / POCO / Oppo / Realme / Vivo / OnePlus / Samsung / Huawei / Honor phones also tap **Open** next to *Phone-maker battery settings* and allow **Autostart** / set battery to **No restrictions / Unrestricted**. Then open Recents and **lock** WorkDeck (padlock icon) if your phone has that.
4. Tap **Start WorkDeck**.

## 3. Sign in (once per account)

1. WorkDeck opens **CRAFTWW → Teams**, showing the Microsoft sign-in page. Enter that account's email and password (and the company's sign-in / MFA if asked).
2. When Microsoft asks **"Stay signed in?" → tap Yes** (tick "Don't show this again"). This is what keeps you signed in permanently.
3. Tap **CRAFTWW** in the bottom bar → **Outlook**. It normally signs in automatically with the same account (otherwise sign in once the same way).
4. Tap **OMC** → **Teams** and sign in with the OMC Microsoft account; then **OMC → Outlook**. Do the same for **ACXIOM**. Each account remembers its own sign-in.

### Turn on notifications inside Teams / Outlook (once)
* **Teams**: notifications are on by default. To check: profile picture → **Settings → Notifications and activity**.
* **Outlook**: WorkDeck notifies about new Inbox mail by itself. For calendar reminders too, turn on **Settings (gear) → General → Notifications → desktop notifications** in Outlook (once per account).

## 4. Using WorkDeck

The bottom bar:

| Button | What it does |
|---|---|
| **CRAFTWW** / **OMC** / **ACXIOM** … | Tap: drop-up with that account's **Teams** and **Outlook** (unread counts in brackets) and *settings…*. **Long-press**: jump straight to the app that account used last. The highlighted button (with the Teams/Outlook icon) is the one on screen. Red badge = that account's unread total. |
| **+** | Add another Microsoft account. |
| **📎** (while typing a message) | Attach files from the phone to the chat / email. |
| 🔊 **Speaker** (only during a call) | Tap: cycle Speaker → Earpiece → Bluetooth. Long-press: pick from a list. |
| 🖥 / 📱 | Switch **desktop ⟷ mobile view** for the current app. |
| 🔍 **Zoom** | Shows the zoom bar: **−  Fit  +**, text size **A− / A+**, and the desktop page **Width** (smaller width = bigger text). Pinch-zoom works too. |
| ⋮ **Menu** | Reload, start page, hide toolbar (full screen), open in browser, **Manage accounts**, **Add account**, Setup & permissions, Settings, **Quit**. |

* The **back** button goes back inside Teams/Outlook; at the start it just goes to the home screen and WorkDeck **keeps running**.
* The notification **"WorkDeck is connected"** must stay (Android requires it for background apps). You can long-press it → turn the "Running in background" category to *silent/minimized*. Its **Quit** button fully stops WorkDeck (you stay signed in).

### Accounts
* **Switching:** tap the account's name, then *Teams* or *Outlook*. Everything keeps running in the background, so switching is instant and every account keeps notifying.
* **Add an account:** **+** in the bottom bar (or ⋮ → *Add account*): type a short name (e.g. the company), choose work or personal, then sign in.
* **Account settings:** tap the account → *NAME settings…* (or ⋮ → *Manage accounts* → the account): **name**, **colour**, work/personal, **use Teams / use Outlook**, desktop/mobile view per app, custom start pages, **keep running for notifications**, Teams/Outlook notifications on/off, **move left/right**, **sign out of this account**, **delete account**.
* **Memory:** every account that keeps running holds its Teams and Outlook in memory. An account starts running the first time you open it. On phones with little memory, switch *Keep running for notifications* off for accounts you rarely use (they still work when you open them).
* Links opened from other apps (meeting links, `mailto:`, web links) open in the account that's on screen.

### Links and extra tabs
* A link tapped in Teams or Outlook opens as a tab of the **same account** (same sign-in), named after the page. Its account's drop-up lists it under Teams and Outlook with a **×**; the bar above the page also has a ×, reload and *open in browser*.
* **New tab…** (in the drop-up or ⋮) opens an address, a search or a quick link in that account.
* An account keeps up to 10 link tabs (the oldest closes first); link tabs you haven't looked at for 45 minutes give their memory back and reload when you open them.
* Sign-in windows and Teams/Outlook pop-outs still open over the page, as before.

### Sending files
* **📎** in the bottom bar (shown while you type in a chat or email) → pick files → they're added to the message.
* **Share → WorkDeck** from any app → choose e.g. *CRAFTWW · Teams* → open the chat and tap its message box if it isn't open already.

### Calls
* **Incoming call:** the phone rings and shows *Incoming Teams call* (full-screen on the lock screen). Tap **Answer** (WorkDeck opens Teams and presses Accept for you — if not, tap Teams' green **Accept** button) or tap **Decline**.
* **During a call:** use the speaker button (bottom bar or the "Teams call in progress" notification) to switch **Speaker / Earpiece / Bluetooth**. Holding the phone to your ear with earpiece selected turns the screen off. The screen stays on otherwise.
* **Leaving the app during a call** shows it in a small picture-in-picture window; the call (and your microphone) keeps working.
* **Bluetooth earbuds / car:** connect them before or during the call — WorkDeck switches to them automatically.
* **In Teams' own device settings** (in a call: *More → Settings → Device settings*), you can also pick *Phone speaker (loud)*, *Phone earpiece* or *Bluetooth* as the speaker, and *Front/Back camera*.
* **Meeting links** from mail or other apps: choose **WorkDeck** when Android asks which app to open them with.

### Settings (⋮ → Settings)
**Accounts** (opens *Manage accounts*), **notification sounds** (Teams message sound, Outlook mail sound, *Notification loudness* 100–400%), *Open links inside WorkDeck*, desktop width, *Keep running in background*, *Reliable mode* (keeps the processor awake for instant notifications — uses more battery, on by default), *Start after restart*, call alerts/ringing, default call audio, picture-in-picture, **Sign out of all accounts**. Everything that belongs to one account (type, apps, view, notifications, custom start pages) is in that account's settings.

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| No notifications after a while | ⋮ → **Setup & permissions**: everything should say *Allowed ✓*. Do the phone-maker step (Autostart, battery *Unrestricted*, lock in Recents). See [dontkillmyapp.com](https://dontkillmyapp.com) for your phone. Keep *Reliable mode* on. |
| Outlook mail notifications missing | Open that account's Outlook once so it starts running; check the account's settings: *Keep running for notifications* and *Outlook notifications* on. Keep Outlook's folder list or message list on its **Inbox**. |
| OMC / ACXIOM / a new account shows *"please update Android System WebView"* | Separate sign-ins need a recent WebView. Update **Android System WebView** and **Google Chrome** in the Play Store, then ⋮ → Quit and reopen WorkDeck. The first account (CRAFTWW) keeps working meanwhile. |
| Two accounts show the same person | They can't share a sign-in (each has its own storage) — you signed in with the same email twice. Open the account's settings → *Sign out of this account* and sign in with the right email. |
| Call: others can't hear me | Setup must show *Microphone: Allowed ✓*. In the Teams call, open **More → Settings → Device settings** and pick *Automatic*, *Phone microphone* or your Bluetooth headset. |
| Bluetooth headset not used for the call | Connect it, then pick *Bluetooth: <name>* as microphone (or speaker) in Teams' device settings, or tap the speaker button in WorkDeck's bottom bar. If the headset can't do calls, WorkDeck falls back to the phone. |
| 📎 added nothing | Open the chat (or start a reply/new email) and tap its message box first; then tap 📎 again. |
| Notification sound too loud/quiet | ⋮ → Settings → *Notification loudness* (lift your finger to hear it), or pick another sound. |
| No sound / wrong speaker | Tap the speaker button in the bottom bar during the call. |
| "Your browser version isn't supported" / blank page | WorkDeck shows a prompt with **Update WebView / Update Chrome** buttons when Microsoft reports this. Update **Android System WebView** and **Google Chrome** in the Play Store, then ⋮ → Reload. (Teams only supports the latest few Chrome versions.) |
| Sign-in loops / wrong account | Account settings → **Sign out of this account** (or ⋮ → Settings → *Sign out of all accounts*), then sign in again. |
| Company says device not allowed | Your organisation's policy (e.g. Intune / Conditional Access) may only allow approved apps. WorkDeck can't bypass that. |
| Text too small in desktop view | Zoom button → choose a smaller **Width** (e.g. 800px) or use **A+**. Or switch to mobile view. |

## 6. Honest limitations

* **Screen sharing from the phone** is not possible: Android WebView does not support sharing your screen (`getDisplayMedia`). You can still *see* others' shared screens.
* Background blur / video effects may be unavailable.
* Notifications come from the Teams/Outlook web pages staying connected in the background (Microsoft has no push service for third-party apps). That's why the background service and battery settings matter. Phones that force-kill apps can still delay them.
* Answering is done in Teams' own call screen; the *Answer* button opens it and tries to press Accept automatically.
* Microsoft can change their websites at any time; WorkDeck uses the real web apps, so it follows those changes.

## 7. What has been tested (and what hasn't)

**Automated, on real Android (emulators in GitHub Actions — Android 15, 14 and 12, the real Android System WebView):**
setup screen; both tabs load; keep-alive foreground service (with microphone + camera service types); Teams presents as desktop Chrome on Windows (user-agent, `navigator.platform`, client hints); the integration script is injected into Microsoft pages; the Teams service worker gets the notification shim; a web notification becomes an Android notification on the *Teams messages* channel; an incoming-call notification becomes a ringing call alert; WebRTC `getUserMedia` gets a live microphone track; the call is detected ("Teams call in progress" with speaker/earpiece buttons) and stays active with the app in the background; zoom buttons; the app stays alive 60 s in the background and after being swiped away from Recents, and the **same Teams page is still in memory** afterwards (no reload); the delivered release APK installs, runs and keeps its service. (These device tests found and fixed a real crash in the zoom *Fit* button.)

**Added in 1.1.0 and verified on the Android 14 emulator (real WebView):** Teams sees microphone + camera as allowed (WebView itself has no Permissions API), a named microphone, Front/Back camera and speaker outputs; the mic and camera open with those device ids; choosing *Phone earpiece* as Teams' speaker switches Android's call audio; a rising unread count without a site notification posts "N unread in Teams" (and removes it at 0); Teams' Accept/Decline call screen in the background rings and stops when it disappears.

**Added in 1.2.0 and verified on emulators (Android 15 with WebView 124, Android 14 with WebView 113, Android 12 with WebView 91):** the bottom bar shows **CRAFTWW, OMC, ACXIOM** and **+**, and the CRAFTWW drop-up lists its Teams and Outlook; **Outlook new mail** (a stand-in Inbox served at outlook.office.com, since the CI has no Microsoft account) — the Inbox unread count going up posts *"3 unread in Outlook"* on the *Outlook mail* channel naming the sender, and the notification goes away when the mail is read; opening OMC creates OMC's own WebViews in its **own profile**, and on Android 15 a sign-in cookie stored in CRAFTWW is **not visible to OMC** (separate sign-ins); on the older emulator WebViews (no profile support) OMC shows the *please update Android System WebView* page instead of sharing CRAFTWW's sign-in. All 1.1 checks (calls, mic/camera/speaker, notifications, background survival) still pass with the new account system.

**Automated in Chromium:** 29 tests of the page-integration script (incl. the Outlook Inbox unread reader: folder pane, aria-label form, message-list fallback, first reading is a baseline, sender preview; notifications incl. clicks & service workers, permissions & device list, camera/speaker mapping, call-screen detection, unread preview, identity, viewport/zoom, badges, mic tracking, blob downloads, call buttons, token isolation) and 34 checks of the URL-routing / call-detection logic.

**Not tested (needs your phone and your Microsoft accounts):** signing in with real accounts (and two different companies at the same time), receiving real Teams/Outlook messages — in particular whether your Outlook's folder list shows the Inbox number the way WorkDeck reads it — and a real two-person Teams call with sound on speaker/Bluetooth. Please try a short test call with a colleague first.

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
  web/TabManager.java      process-wide owner of every account's tabs and link tabs (outlives activities)
  web/Attachments.java     phone files being attached (read by the page in chunks through the bridge)
  Account.java / Accounts.java  the accounts, their per-account settings and WebView profiles
  web/UserAgents.java      desktop Chrome/Windows + Chrome-for-Android identities from the real engine version
  web/WebViewSupport.java  document-start scripts, UA client hints and per-account WebView profiles
                           (separate cookies/storage) via the WebView APK's support-library glue
  web/JsBridge.java        token-protected window.WorkDeckBridge used by the injected script
  web/SwShim.java          adds a notification shim to Microsoft service-worker scripts
  web/UrlRouter.java       which URLs are Teams / Outlook / Microsoft / external (pure Java, unit-tested)
  call/CallManager.java    call detection + speaker/earpiece/Bluetooth/wired routing, wake locks
  notify/Notifier.java     Android notifications, incoming-call alerts, downloads
  notify/SoundPlayer.java  chosen message/mail sound, played with a loudness boost
  service/KeepAliveService foreground service (special-use + microphone/camera types), boot restart
  ui/MainActivity.java     account drop-ups, bottom bar, zoom bar, popups, PiP, full-screen video
  ui/AccountsActivity.java manage accounts (add, rename, colour, order, per-account settings, sign out, delete)
  ui/DropUp.java           an account's drop-up (Teams, Outlook, link tabs with ×, New tab, settings)
  ui/ShareActivity.java    "Share → WorkDeck" from other apps
  ui/SetupActivity.java    first-run setup & permissions; ui/SettingsActivity.java settings
app/src/main/assets/workdeck/
  inject.js                document-start script: Notification API, desktop identity, viewport/zoom,
                           Badging API, Outlook Inbox unread count, blob downloads, mic tracking, call buttons,
                           microphone choice, attaching phone files, file-picker fallback
  sw-shim.js               forwards service-worker notifications to the page
```

### Why the two reference apps can't take calls
* **Desktop Browser (com.computer.shortkeyszz)** — a Flutter app (flutter_inappwebview) that only changes the user-agent. Its manifest has **no `RECORD_AUDIO`, `CAMERA`, `MODIFY_AUDIO_SETTINGS`, Bluetooth or `POST_NOTIFICATIONS`** permission, so Teams can never get the microphone and nothing can notify you.
* **FoxBox (tech.ula.foxbox_pro)** — runs a Linux distribution (proot, UserLAnd-based) and shows desktop Firefox through a VNC viewer. It also has **no `RECORD_AUDIO`**, and audio from a proot/VNC desktop doesn't reach Android's microphone/speaker routing, so calls can't work.

WorkDeck instead uses Android's native Chromium WebView (same engine as Chrome), grants WebRTC access properly, and handles Android audio routing, background execution and notifications natively.

## License
MIT. The tiny interfaces in `org/chromium/support_lib_boundary` mirror Chromium's public WebView support-library boundary (BSD-licensed).
