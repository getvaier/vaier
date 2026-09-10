package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact length of a zip written uncompressed with data descriptors (#360): the browser can be told a
 * download's size before the first byte streams, and show progress. A zip past what the classic format
 * can address has no size to announce, and says so rather than guess.
 */
class ZipLayoutTest {

    @Test
    void anUncompressedZipsLengthFollowsFromNamesAndSizes() {
        // one entry, "a.jpg" (5 bytes of name), 100 bytes of data:
        // local header 30 + 5 + data 100 + descriptor 16 + central 46 + 5 + end 22 = 224
        ZipLayout layout = new ZipLayout(List.of(new ZipLayout.Entry("a.jpg", 100)));

        assertThat(layout.sizeBytes()).contains(224L);
    }

    @Test
    void namesAreCountedAsUtf8Bytes() {
        // "ø.jpg" is 6 bytes in UTF-8, not 5 characters
        ZipLayout layout = new ZipLayout(List.of(new ZipLayout.Entry("ø.jpg", 0)));

        assertThat(layout.sizeBytes()).contains(30L + 6 + 0 + 16 + 46 + 6 + 22);
    }

    @Test
    void anEmptyZipIsJustItsEnd() {
        assertThat(new ZipLayout(List.of()).sizeBytes()).contains(22L);
    }

    /** Past four gigabytes, or 65535 entries, the classic format cannot say the size; then neither do we. */
    @Test
    void whatTheClassicFormatCannotAddressHasNoSizeToAnnounce() {
        assertThat(new ZipLayout(List.of(new ZipLayout.Entry("big.bin", 0xFFFFFFFFL))).sizeBytes()).isEmpty();
        assertThat(new ZipLayout(List.of(new ZipLayout.Entry("a", 3_000_000_000L), new ZipLayout.Entry("b", 2_000_000_000L)))
            .sizeBytes()).isEmpty();
        assertThat(new ZipLayout(Collections.nCopies(0xFFFF, new ZipLayout.Entry("x", 1))).sizeBytes()).isEmpty();
        assertThat(new ZipLayout(List.of(new ZipLayout.Entry("a", 2_000_000_000L), new ZipLayout.Entry("b", 2_000_000_000L)))
            .sizeBytes()).isPresent();
    }
}
