package net.vaier.domain;

import java.util.List;

/**
 * The single source of truth for the operator-facing <b>Concepts page</b>: about fifteen words an
 * operator actually meets in the Vaier UI, grouped and ordered the way the page
 * shows them.
 *
 * <p>This is deliberately a pure domain class with no Spring dependencies — the copy here is part of
 * the product's ubiquitous language, not an infrastructure detail. Every term named here must also
 * exist verbatim as a bold entry in {@code UBIQUITOUS_LANGUAGE.md}; a drift test enforces that, so
 * the in-app glossary can never name a term the canonical document doesn't define.
 */
public final class OperatorGlossary {

    private OperatorGlossary() {
    }

    public static List<ConceptGroup> groups() {
        return List.of(
            new ConceptGroup("Your machines", List.of(
                Concept.of("Machine",
                    "Any computer Vaier looks after: a server, a PC, a phone, a storage box, a printer.",
                    "Everything else in Vaier hangs off one — its apps, its websites, its backups."),
                Concept.of("Vaier server",
                    "The computer Vaier itself runs on. It holds the one public address everything comes "
                        + "in through.",
                    "If it is down, nothing is reachable from outside, so it is the one machine that is "
                        + "never optional."),
                Concept.of("VPN",
                    "Your private network: every machine that joins it can reach the others through the "
                        + "Vaier server, wherever it is.",
                    "A machine at another house, or a phone on the road, is as close as one on your desk."),
                Concept.of("Vaier app",
                    "Vaier's own app for an Android phone or a Windows PC. It joins the device to your "
                        + "VPN with a four-digit join code.",
                    "It is the only way a phone or PC joins, and it keeps its own key, so there is no "
                        + "file to copy around."),
                Concept.of("Switched off on purpose",
                    "A server you said is off by choice, with one tap on its \"not answering\" row.",
                    "Vaier stays quiet about it — no Needs you row, no mail — until it answers again."))),

            new ConceptGroup("On a machine", List.of(
                Concept.of("Apps",
                    "The programs a machine runs in Docker containers.",
                    "Vaier tells you when one has an update waiting, and updates it with one click."),
                Concept.of("Websites",
                    "The apps you have put on the internet at their own address under your domain.",
                    "Each one can be open to anyone or only to people who sign in, and can show on "
                        + "the Launchpad."))),

            new ConceptGroup("Who gets in", List.of(
                Concept.of("Launchpad",
                    "The page of tiles linking to all your websites.",
                    "It is the everyday front door: bookmark it, and everyone sees only what they may open."),
                Concept.of("Sign in",
                    "Proving who you are — with Google or GitHub, or the first-run password — before Vaier "
                        + "lets you in.",
                    "Who may open what is decided after that, on the Users page."),
                Concept.of("Trusted address",
                    "An internet address you told Vaier never to keep out.",
                    "Vaier turns away strangers that misbehave on its own. Trust an address when it was "
                        + "you, and untrust it on the Security page when that stops being true."))),

            new ConceptGroup("Backups", List.of(
                Concept.of("Backup server",
                    "The one machine your other machines are backed up to, every night.",
                    "If it is unreachable, no backup happens anywhere, so Vaier tells you."),
                Concept.of("Incomplete backup",
                    "A backup that finished but could not read some of the files it was meant to copy, "
                        + "so those files are not in it.",
                    "It is the failure that looks like success: you would only find out on the day you "
                        + "needed the file."),
                Concept.of("Back up as root",
                    "Letting the backup read every file on a machine, including ones that belong to other "
                        + "users — app data, databases, other people's home folders.",
                    "Turn it on when a backup came back incomplete. The price: the machine lets Vaier's "
                        + "login run the backup program as root, and only that program, and only when you "
                        + "say yes."))),

            new ConceptGroup("Help", List.of(
                Concept.of("Needs you",
                    "The list at the top of your fleet of everything that wants you, most urgent first.",
                    "When it is empty, nothing needs doing."),
                Concept.of("Marvin",
                    "Who answers in Chat: the Paranoid Android, answering questions about your fleet from "
                        + "its own facts.",
                    "Gloomy and never wrong. He can look and propose, but nothing changes until you "
                        + "click."))));
    }
}
