package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnockTest {

    @Test
    void readsAScenarioAsWhatTheSourceTried_inPlainWords() {
        record Row(String scenario, String words) {}
        for (Row row : new Row[] {
            new Row("crowdsecurity/ssh-bf", "tried passwords"),
            // A WordPress login grind is a password attempt first, a WordPress scan second.
            new Row("crowdsecurity/http-bf-wordpress_bf", "tried passwords"),
            new Row("crowdsecurity/CVE-2022-41082", "tried a known weakness"),
            new Row("crowdsecurity/jira_cve-2021-26086", "tried a known weakness"),
            new Row("crowdsecurity/http-cve-probing", "tried a known weakness"),
            new Row("crowdsecurity/vpatch-env-access", "tried a known weakness"),
            new Row("crowdsecurity/http-admin-interface-probing", "looked for admin pages"),
            new Row("crowdsecurity/http-wordpress-scan", "looked for WordPress"),
            new Row("crowdsecurity/http-sensitive-files", "looked for private files"),
            new Row("crowdsecurity/http-backdoors-attempts", "looked for a back door"),
            new Row("crowdsecurity/http-sqli-probing", "tried to slip code into the site"),
            new Row("crowdsecurity/http-xss-probing", "tried to slip code into the site"),
            new Row("crowdsecurity/http-path-traversal-probing", "tried to reach files outside the site"),
            new Row("crowdsecurity/http-crawl-non_statics", "crawled the site"),
            new Row("crowdsecurity/http-bad-user-agent", "used a known attack tool"),
            new Row("crowdsecurity/http-probing", "probed for weak spots"),
            // Unknown, blank or missing: a safe phrase, never the raw slug.
            new Row("someone/brand-new-thing", "tried something suspicious"),
            new Row("", "tried something suspicious"),
            new Row(null, "tried something suspicious"),
        }) {
            assertThat(Knock.of(row.scenario()).words()).as(row.scenario()).isEqualTo(row.words());
        }
    }
}
