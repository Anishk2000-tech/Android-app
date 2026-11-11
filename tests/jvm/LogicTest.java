import com.workdeck.app.Site;
import com.workdeck.app.call.CallManager;
import com.workdeck.app.call.RoutePolicy;
import com.workdeck.app.notify.CallHeuristics;
import com.workdeck.app.web.UrlRouter;

/**
 * Plain-JVM checks of WorkDeck's pure-Java logic: URL routing rules and the
 * incoming-call heuristic. Run by scripts/run-tests.sh.
 */
public final class LogicTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private static void eq(Object actual, Object expected, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, what + (ok ? "" : "  (got " + actual + ", expected " + expected + ")"));
    }

    public static void main(String[] args) {
        // Site detection
        eq(UrlRouter.siteFor("https://teams.microsoft.com/v2/"), Site.TEAMS, "teams.microsoft.com -> Teams");
        eq(UrlRouter.siteFor("https://teams.live.com/v2/"), Site.TEAMS, "teams.live.com -> Teams");
        eq(UrlRouter.siteFor("https://teams.cloud.microsoft/"), Site.TEAMS, "teams.cloud.microsoft -> Teams");
        eq(UrlRouter.siteFor("https://outlook.office.com/mail/"), Site.OUTLOOK, "outlook.office.com -> Outlook");
        eq(UrlRouter.siteFor("https://outlook.live.com/mail/0/"), Site.OUTLOOK, "outlook.live.com -> Outlook");
        eq(UrlRouter.siteFor("https://login.microsoftonline.com/common"), null, "login is neither site");
        eq(UrlRouter.siteFor("https://evilteams.microsoft.com.example.org/"), null, "lookalike host rejected");

        // Microsoft vs external
        check(UrlRouter.isMicrosoft("https://contoso.sharepoint.com/sites/x"), "SharePoint is Microsoft");
        check(UrlRouter.isMicrosoft("https://login.microsoftonline.com/x"), "login.microsoftonline.com is Microsoft");
        check(!UrlRouter.isMicrosoft("https://www.google.com/"), "google.com is external");
        check(!UrlRouter.isMicrosoft("https://microsoft.com.evil.io/"), "suffix trick is external");
        check(!UrlRouter.isMicrosoft("https://eu.safelinks.protection.outlook.com/?url=x"),
                "Safe Links open externally (browser follows redirect)");
        check(UrlRouter.isLogin("https://login.live.com/oauth20"), "login.live.com is a sign-in page");

        // Injection policy (bridge token exposure)
        check(UrlRouter.isInjectable("https://teams.microsoft.com/v2/"), "inject into Teams");
        check(UrlRouter.isInjectable("https://login.windows.net/x"), "inject into login.windows.net");
        check(!UrlRouter.isInjectable("https://foo.blob.core.windows.net/x.html"), "no injection on customer blob storage");
        check(!UrlRouter.isInjectable("http://teams.microsoft.com/"), "no injection over plain http");
        String[] rules = UrlRouter.injectionOriginRules();
        boolean wildcardOk = true;
        for (String r : rules) if (!r.startsWith("https://")) wildcardOk = false;
        check(wildcardOk && rules.length > 10, "origin rules are https-only (" + rules.length + " rules)");

        // Deep links
        eq(UrlRouter.msteamsToHttps("msteams:/l/meetup-join/19%3ameeting_abc/0?context=x"),
                "https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc/0?context=x", "msteams: -> https");
        eq(UrlRouter.mailtoToOutlook("mailto:bob@contoso.com?subject=Hi%20there&body=Line", "https://outlook.office.com/mail/"),
                "https://outlook.office.com/mail/deeplink/compose?to=bob%40contoso.com&subject=Hi%20there&body=Line",
                "mailto -> Outlook compose (work)");
        eq(UrlRouter.mailtoToOutlook("mailto:a@b.com", "https://outlook.live.com/mail/"),
                "https://outlook.live.com/mail/deeplink/compose?to=a%40b.com", "mailto -> Outlook compose (personal)");

        // Host parsing edge cases
        eq(UrlRouter.host("https://user:pw@Teams.Microsoft.com:443/x?y#z"), "teams.microsoft.com", "host strips userinfo/port/case");
        eq(UrlRouter.host("https://teams.microsoft.com./v2"), "teams.microsoft.com", "host strips trailing dot");
        eq(UrlRouter.host("https://outlook.office.com?x=1"), "outlook.office.com", "host ends at '?'");
        eq(UrlRouter.host("https://teams.microsoft.com\\@evil.com/"), "teams.microsoft.com", "backslash ends authority like browsers");
        eq(UrlRouter.host("mailto:a@b.com"), "", "no host for mailto");
        eq(UrlRouter.host("https://[::1]:8080/"), "[::1]", "IPv6 literal host");
        eq(UrlRouter.siteFor("https://evil.com/#https://teams.microsoft.com/"), null, "fragment can't fake the host");

        // Links: sign-in windows stay pop-ups, everything else becomes an account tab
        check(UrlRouter.looksLikeAuth("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?x=1"), "Microsoft sign-in stays a pop-up");
        check(UrlRouter.looksLikeAuth("https://github.com/login/oauth/authorize?client_id=1"), "OAuth of another service stays a pop-up");
        check(UrlRouter.looksLikeAuth("https://app.example.com/sso?SAMLRequest=abc"), "SAML single sign-on stays a pop-up");
        check(!UrlRouter.looksLikeAuth("https://contoso.sharepoint.com/:w:/r/sites/Team/Doc.docx"), "a SharePoint document opens as a tab");
        check(!UrlRouter.looksLikeAuth("https://www.bbc.com/news/world-123"), "a news link opens as a tab");
        check(!UrlRouter.looksLikeAuth("https://login-tips.example.com/"), "'login' in the host name alone is not a sign-in window");
        eq(UrlRouter.fromUserInput("contoso.sharepoint.com/sites/x"), "https://contoso.sharepoint.com/sites/x", "New tab: address without https://");
        eq(UrlRouter.fromUserInput("  https://www.office.com/  "), "https://www.office.com/", "New tab: full address kept");
        eq(UrlRouter.fromUserInput("quarterly report"), "https://www.bing.com/search?q=quarterly%20report", "New tab: words become a search");
        eq(UrlRouter.fromUserInput(""), "", "New tab: empty input does nothing");

        // Notification loudness: percent of the sound -> LoudnessEnhancer gain (millibels)
        eq(com.workdeck.app.notify.Loudness.millibels(100), 0, "100% = no boost");
        eq(com.workdeck.app.notify.Loudness.millibels(300), 954, "300% = +9.5 dB");
        eq(com.workdeck.app.notify.Loudness.millibels(400), 1204, "400% = +12 dB");

        // Incoming-call heuristic
        check(CallHeuristics.looksLikeIncomingCall("Alice Smith", "Incoming call", false), "\"Incoming call\" is a call");
        check(CallHeuristics.looksLikeIncomingCall("Bob is calling you", "", false), "\"is calling you\" is a call");
        check(CallHeuristics.looksLikeIncomingCall("Video call from Carol", "", false), "\"Video call from\" is a call");
        check(CallHeuristics.looksLikeIncomingCall("Dave", "Teams call", true), "requireInteraction + call is a call");
        check(!CallHeuristics.looksLikeIncomingCall("Dave", "Can you call me later?", false), "chat mentioning call is not a call");
        check(!CallHeuristics.looksLikeIncomingCall("Erin", "Lunch?", true), "requireInteraction alone is not a call");

        // Call audio: where a call's sound goes (RoutePolicy)
        {
            java.util.List<CallManager.Route> phone = java.util.Arrays.asList(CallManager.Route.SPEAKER, CallManager.Route.EARPIECE);
            java.util.List<CallManager.Route> withBt = java.util.Arrays.asList(CallManager.Route.SPEAKER, CallManager.Route.EARPIECE, CallManager.Route.BLUETOOTH);
            java.util.List<CallManager.Route> withWired = java.util.Arrays.asList(CallManager.Route.SPEAKER, CallManager.Route.WIRED);
            java.util.List<CallManager.Route> noEarpiece = java.util.Arrays.asList(CallManager.Route.SPEAKER);
            eq(RoutePolicy.auto(phone, false), CallManager.Route.EARPIECE, "voice call: earpiece");
            eq(RoutePolicy.auto(phone, true), CallManager.Route.SPEAKER, "video call: loudspeaker");
            eq(RoutePolicy.auto(withBt, false), CallManager.Route.BLUETOOTH, "Bluetooth headset connected: Bluetooth");
            eq(RoutePolicy.auto(withBt, true), CallManager.Route.BLUETOOTH, "video call with Bluetooth headset: Bluetooth");
            eq(RoutePolicy.auto(withWired, false), CallManager.Route.WIRED, "wired headset: headset");
            eq(RoutePolicy.auto(noEarpiece, false), CallManager.Route.SPEAKER, "no earpiece (tablet): loudspeaker");
            eq(RoutePolicy.onDeviceAdded(CallManager.Route.EARPIECE, phone, withBt), CallManager.Route.BLUETOOTH, "headset connects mid-call: switch to it");
            eq(RoutePolicy.onDeviceAdded(CallManager.Route.SPEAKER, withBt, withBt), CallManager.Route.SPEAKER, "nothing new connected: keep the user's speaker");
            eq(RoutePolicy.onDeviceRemoved(CallManager.Route.BLUETOOTH, phone, false), CallManager.Route.EARPIECE, "headset gone in a voice call: earpiece");
            eq(RoutePolicy.onDeviceRemoved(CallManager.Route.BLUETOOTH, phone, true), CallManager.Route.SPEAKER, "headset gone in a video call: loudspeaker");
            eq(RoutePolicy.onDeviceRemoved(CallManager.Route.SPEAKER, phone, false), CallManager.Route.SPEAKER, "another device gone: keep the speaker");
            eq(RoutePolicy.onVideoStarted(CallManager.Route.EARPIECE, false), CallManager.Route.SPEAKER, "camera on: earpiece -> loudspeaker");
            eq(RoutePolicy.onVideoStarted(CallManager.Route.EARPIECE, true), CallManager.Route.EARPIECE, "camera on, but the user picked the earpiece: stays");
            eq(RoutePolicy.onVideoStarted(CallManager.Route.BLUETOOTH, false), CallManager.Route.BLUETOOTH, "camera on with a headset: stays on the headset");
            // Teams re-applies its saved microphone/speaker at every call start: not the user.
            eq(RoutePolicy.fromPage(CallManager.Route.SPEAKER, null, true, withBt), null, "page's saved speaker at call start: ignored");
            eq(RoutePolicy.fromPage(CallManager.Route.EARPIECE, CallManager.Route.EARPIECE, false, withBt), null, "page repeats the same device: ignored");
            eq(RoutePolicy.fromPage(CallManager.Route.SPEAKER, CallManager.Route.EARPIECE, false, withBt), CallManager.Route.SPEAKER, "user switches device in Teams mid-call: applied");
            eq(RoutePolicy.fromPage(CallManager.Route.BLUETOOTH, CallManager.Route.EARPIECE, false, phone), null, "page asks for a headset that isn't there: ignored");
            eq(RoutePolicy.micToRoute("bluetooth", withBt, false), CallManager.Route.BLUETOOTH, "Bluetooth microphone = Bluetooth headset");
            eq(RoutePolicy.micToRoute("phone", withBt, false), CallManager.Route.EARPIECE, "phone microphone in a voice call = earpiece");
            eq(RoutePolicy.micToRoute("phone", withBt, true), CallManager.Route.SPEAKER, "phone microphone in a video call = loudspeaker");
            eq(RoutePolicy.micToRoute("default", withBt, false), null, "'Automatic' microphone: no device change");
        }

        // Sign-out addresses, and whose sign-out it is.
        {
            check(UrlRouter.isSignOut("https://login.microsoftonline.com/common/oauth2/v2.0/logout?post_logout_redirect_uri=x"), "Microsoft sign-out (v2)");
            check(UrlRouter.isSignOut("https://login.microsoftonline.com/common/oauth2/logout"), "Microsoft sign-out (v1)");
            check(UrlRouter.isSignOut("https://login.microsoftonline.com/3f2a/oauth2/v2.0/logoutsession?sid=1"), "Microsoft sign-out of one session");
            check(UrlRouter.isSignOut("https://login.microsoftonline.com/logout.srf?ct=1"), "Microsoft logout.srf");
            check(UrlRouter.isSignOut("https://login.microsoftonline.com/login.srf?wa=wsignout1.0"), "federated sign-out (parameter)");
            check(UrlRouter.isSignOut("https://login.live.com/oauth20_logout.srf"), "personal account sign-out");
            check(UrlRouter.isSignOut("https://outlook.office.com/owa/logoff.owa"), "Outlook sign-out");
            check(UrlRouter.isSignOut("https://outlook.cloud.microsoft/owa/logoff.owa?x=1"), "Outlook sign-out (new address)");
            check(!UrlRouter.isSignOut("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?post_logout_redirect_uri=https://x/logout"), "sign-in is not a sign-out (even with 'logout' in its parameters)");
            check(!UrlRouter.isSignOut("https://outlook.office.com/mail/"), "Outlook's inbox is not a sign-out");
            check(!UrlRouter.isSignOut("https://teams.microsoft.com/v2/"), "Teams is not a sign-out");
            check(!UrlRouter.isSignOut("https://example.com/logout"), "another site's sign-out is its own business");
            eq(UrlRouter.path("https://Login.microsoftonline.com/Common/OAuth2/Logout?x=/y#z"), "/common/oauth2/logout", "path: lowercase, no parameters");
            eq(UrlRouter.path("https://outlook.office.com"), "", "path: none");

            long never = Long.MAX_VALUE / 4;
            eq(com.workdeck.app.web.SignOutGuard.decide(true, 3000, never, never), com.workdeck.app.web.SignOutGuard.Action.ALLOW,
                    "the user tapped Sign out a moment ago: carried out");
            eq(com.workdeck.app.web.SignOutGuard.decide(true, 3L * 3600 * 1000, never, never), com.workdeck.app.web.SignOutGuard.Action.REFUSE_AND_RELOAD,
                    "untouched for 3 hours, the page signs out by itself: refused, site loaded again");
            eq(com.workdeck.app.web.SignOutGuard.decide(false, 3000, never, never), com.workdeck.app.web.SignOutGuard.Action.REFUSE_AND_RELOAD,
                    "a tab that isn't on screen signs out: refused (the touch was elsewhere)");
            eq(com.workdeck.app.web.SignOutGuard.decide(true, never, never, never), com.workdeck.app.web.SignOutGuard.Action.REFUSE_AND_RELOAD,
                    "never touched since the app started: refused");
            eq(com.workdeck.app.web.SignOutGuard.decide(false, 80000, 20000, never), com.workdeck.app.web.SignOutGuard.Action.ALLOW,
                    "requests that follow the user's own sign-out: carried out");
            eq(com.workdeck.app.web.SignOutGuard.decide(false, never, never, 60000), com.workdeck.app.web.SignOutGuard.Action.REFUSE,
                    "tries again right after the reload: refused, no reload loop");
            eq(com.workdeck.app.web.SignOutGuard.decide(false, never, never, 11 * 60000), com.workdeck.app.web.SignOutGuard.Action.REFUSE_AND_RELOAD,
                    "tries again later: refused, loaded again");
            eq(com.workdeck.app.web.SignOutGuard.ago(45000), "45 s", "ago: seconds");
            eq(com.workdeck.app.web.SignOutGuard.ago(3L * 3600 * 1000 + 12 * 60000), "3 h 12 min", "ago: hours");
            eq(com.workdeck.app.web.SignOutGuard.ago(never), "never", "ago: never");
        }

        System.out.println(failures == 0 ? "\nall logic checks passed" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
