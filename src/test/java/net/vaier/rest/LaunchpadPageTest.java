package net.vaier.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launchpad is the fleet's front door, and the one page a phone can reach before it belongs to the
 * fleet at all — so it is where the <b>Vaier app</b> is handed out (#359).
 *
 * <p>It is handed out as an <b>install card</b>: an app is installed, not linked to, and a nav link
 * spelled "Android" asked the visitor to work out what it would do. The card is painted only where it
 * can be acted on — an Android phone or a Windows computer, on a deployment that carries that app (#370)
 * — so a Mac and an iPhone see nothing rather than an offer they cannot take.
 *
 * <p>There is no JS test harness in this project, so — as with {@code ExplorerShellTest} — the invariants
 * are asserted on the shipped page itself.
 */
class LaunchpadPageTest {

    private static String launchpad() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/launchpad.html"));
    }

    private static String painter(String page) {
        int from = page.indexOf("async function paintInstallCard(");
        assertThat(from).as("the install card has a painter of its own").isPositive();
        return page.substring(from, page.indexOf("\n        }", from));
    }

    private static String appForThisDevice(String page) {
        int from = page.indexOf("function appForThisDevice(");
        assertThat(from).isPositive();
        return page.substring(from, page.indexOf("\n        }", from));
    }

    @Test
    void theVaierApp_isOfferedToEveryVisitorSignedInOrNot() throws IOException {
        // A phone or a laptop fetches the app before it can sign in, so gating this behind a session would
        // be a locked door with the key behind it. Painted outside the signed-in / anonymous branch entirely.
        String page = launchpad();

        assertThat(appForThisDevice(page)).contains("'/app/android/vaier.apk'", "'/app/windows/VaierSetup.exe'");
        assertThat(painter(page)).contains("href=\"${app.href}\"");
        assertThat(page).as("saved, not navigated to").contains("download");
        assertThat(page).contains("paintInstallCard();");
        assertThat(page).as("the nav no longer carries it").doesNotContain("paintAndroidLink");
    }

    @Test
    void theCardIsPaintedOnlyOnAndroidOrWindows() throws IOException {
        // A Mac and an iPhone can take neither app. Offering one there is an offer that fails in the
        // visitor's hands, which is exactly the kind of dead end Vaier catches before it paints anything.
        String page = launchpad();

        assertThat(painter(page)).contains("appForThisDevice()");
        assertThat(appForThisDevice(page)).contains("onAndroid()", "onWindows()");

        for (String os : new String[] { "Android", "Windows" }) {
            int from = page.indexOf("function on" + os + "(");
            assertThat(from).as(os).isPositive();
            String detect = page.substring(from, page.indexOf("\n        }", from));
            assertThat(detect).as("the modern hint first").contains("navigator.userAgentData?.platform");
            assertThat(detect).contains("'" + os + "'");
            assertThat(detect).as("and the user-agent string when there is none")
                .contains("/" + os + "/i.test(navigator.userAgent)");
        }
    }

    @Test
    void theCardIsPaintedOnlyWhenThereIsAnAppToServe() throws IOException {
        // Vaier never offers what it cannot serve: an image built without the package answers 404 here,
        // and the card is simply absent rather than painted over a dead link.
        String body = painter(launchpad());

        assertThat(body).contains("fetch(app.href, { method: 'HEAD'");
        assertThat(body).as("only a 200 paints it").contains("res.ok");
        assertThat(body).as("a failed probe is silence, never a broken card").contains("catch");
    }

    @Test
    void theCardReadsAsAnAppToInstall() throws IOException {
        // The mark, the name, one line of what it is for, and a button that says what it does. No
        // "Android", no file size, no store badge — none of those are a decision the visitor makes.
        String page = launchpad();
        String body = painter(page);

        assertThat(body).contains("class=\"install-name\">Vaier<");
        assertThat(appForThisDevice(page)).contains("Get on the VPN from this phone", "verb: 'Install'",
            "Get on the VPN from this computer", "verb: 'Download'");
        assertThat(body).contains("${app.line}");
        assertThat(body).containsPattern("class=\"btn btn-primary\"[^>]*download>\\$\\{app.verb}<");
        assertThat(body).as("the app's own mark, not a generic phone glyph").contains("ICON_APP");

        // The favicon's geometry — hub and four spokes — in the app's amber, which is the one colour the
        // card adds. The Install button stays the product's primary cyan so the amber is spent once.
        assertThat(page).containsPattern("ICON_APP\\s*=\\s*'<svg [^']*viewBox=\"0 0 16 16\"");
        assertThat(page).contains("color: #d9a05b;");
    }

    @Test
    void theCardSitsAtTheFootOfThePage() throws IOException {
        // Below everything the fleet has to say, where a footer would be. The launchpad is about the fleet;
        // the app is the one thing on it that is about this phone, so it comes last rather than first, and
        // a phone that already has the app scrolls past nothing to reach its services.
        String page = launchpad();

        assertThat(page).contains("<div id=\"installCard\"></div>");
        assertThat(page.indexOf("<div id=\"installCard\"></div>")).isGreaterThan(page.indexOf("<div id=\"grid\">"));
    }

    @Test
    void theCardCannotBeDismissed() throws IOException {
        // A footer card is in nobody's way, so there is nothing to dismiss — and a dismissal that outlived
        // the visit was the first thing the operator tripped over: the card went and never came back.
        // Nothing about the card is remembered anywhere.
        String page = launchpad();
        String body = painter(page);

        assertThat(body).doesNotContain("sessionStorage").doesNotContain("localStorage");
        assertThat(body).doesNotContain("dismiss").doesNotContain("Dismiss");
        assertThat(page).doesNotContain("installCardDismissed");
    }

    @Test
    void theLaunchpadNeverPollsForTheApp() throws IOException {
        // The backend polls and SSE pushes; the browser asks once, on load.
        assertThat(painter(launchpad())).doesNotContain("setInterval");
    }
}
