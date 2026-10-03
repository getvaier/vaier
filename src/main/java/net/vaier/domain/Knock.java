package net.vaier.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * What a blocked source tried, in words an operator reads without knowing CrowdSec: read off a
 * {@link BlockDecision}'s scenario name the same way {@link ThreatKind} is — by the whole words in it, so a
 * hub scenario Vaier has never seen still lands on the nearest phrase. The first rule that matches wins;
 * a name none of them recognises is {@link #SUSPICIOUS}, never the raw slug.
 */
public enum Knock {

    PASSWORDS("tried passwords"),
    KNOWN_WEAKNESS("tried a known weakness", "cve", "vpatch"),
    ADMIN_PAGES("looked for admin pages", "admin"),
    WORDPRESS("looked for WordPress", "wordpress", "wp", "wpconfig"),
    PRIVATE_FILES("looked for private files", "sensitive"),
    BACK_DOOR("looked for a back door", "backdoor", "backdoors"),
    CODE_INJECTION("tried to slip code into the site", "sqli", "xss"),
    PATH_TRAVERSAL("tried to reach files outside the site", "traversal"),
    CRAWLING("crawled the site", "crawl"),
    ATTACK_TOOL("used a known attack tool", "agent"),
    PROBING("probed for weak spots", "probing", "probe", "scan"),
    SUSPICIOUS("tried something suspicious");

    private final String words;
    private final Set<String> nameWords;

    Knock(String words, String... nameWords) {
        this.words = words;
        this.nameWords = Set.of(nameWords);
    }

    /** Password attempts first: a WordPress login grind is the credential attack {@link ThreatKind} mails. */
    public static Knock of(String scenario) {
        if (ThreatKind.of(scenario) == ThreatKind.CREDENTIAL_ATTACK) return PASSWORDS;
        if (scenario == null || scenario.isBlank()) return SUSPICIOUS;
        List<String> name = Arrays.asList(
            scenario.substring(scenario.lastIndexOf('/') + 1).toLowerCase().split("[-_.]"));
        return Arrays.stream(values())
            .filter(knock -> name.stream().anyMatch(knock.nameWords::contains))
            .findFirst()
            .orElse(SUSPICIOUS);
    }

    /** A lower-case verb phrase, so it can open a sentence or sit inside one. */
    public String words() {
        return words;
    }
}
