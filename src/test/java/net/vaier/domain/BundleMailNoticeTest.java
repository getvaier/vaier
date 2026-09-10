package net.vaier.domain;

import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mail that carries a bundle's link: the name, what it holds, the link, and how long it works. */
class BundleMailNoticeTest {

    private static final MachineId NAS = MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862");

    @Test
    void theMailNamesTheZip_itsSize_andALinkThatWorksForADay() {
        Bundle bundle = Bundle.offer(NAS, "NAS", List.of("/volume1/photo/a.jpg"), "pictures-2025-09-10", 1L)
            .sized(List.of(new RemoteStat(false, 2_000_000)));

        BundleMailNotice notice = BundleMailNotice.of(bundle, "example.com");

        assertThat(notice.subject()).isEqualTo("Your files from NAS: pictures-2025-09-10.zip");
        assertThat(notice.body())
            .contains("pictures-2025-09-10.zip (1 file, 2.0 MB)")
            .contains("https://vaier.example.com/chat/bundles/" + bundle.id())
            .contains("works for a day")
            .contains("signed in to Vaier")
            .contains("Marvin");
    }
}
