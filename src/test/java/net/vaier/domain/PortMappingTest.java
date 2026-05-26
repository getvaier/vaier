package net.vaier.domain;

import net.vaier.domain.DockerService.PortMapping;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortMappingTest {

    @Test
    void collapseContiguous_singlePort_unchanged() {
        var single = new PortMapping(8080, 8080, "tcp", "0.0.0.0");

        List<PortMapping> collapsed = PortMapping.collapseContiguous(List.of(single));

        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.get(0).privatePort()).isEqualTo(8080);
        assertThat(collapsed.get(0).isRange()).isFalse();
    }

    @Test
    void collapseContiguous_contiguousTcpRun_collapsesToOneRange() {
        // Roon-style: 9100/tcp, 9101/tcp ... 9339/tcp = one range row
        List<PortMapping> input = new ArrayList<>();
        for (int p = 9100; p <= 9339; p++) {
            input.add(new PortMapping(p, p, "tcp", "0.0.0.0"));
        }

        List<PortMapping> collapsed = PortMapping.collapseContiguous(input);

        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.get(0).isRange()).isTrue();
        assertThat(collapsed.get(0).privatePort()).isEqualTo(9100);
        assertThat(collapsed.get(0).lastPrivatePort()).isEqualTo(9339);
        assertThat(collapsed.get(0).type()).isEqualTo("tcp");
    }

    @Test
    void collapseContiguous_protocolsDoNotMerge() {
        // 9003/udp must NOT merge with 9004/tcp even though numerically adjacent.
        var udp = new PortMapping(9003, 9003, "udp", "0.0.0.0");
        var tcp = new PortMapping(9004, 9004, "tcp", "0.0.0.0");

        List<PortMapping> collapsed = PortMapping.collapseContiguous(List.of(udp, tcp));

        assertThat(collapsed).hasSize(2);
        assertThat(collapsed).noneMatch(PortMapping::isRange);
    }

    @Test
    void collapseContiguous_nonContiguousEntriesStaySeparate() {
        // 55000/tcp + 9100-9105/tcp in the same container — only the run collapses,
        // 55000 stays as its own row.
        List<PortMapping> input = new ArrayList<>();
        input.add(new PortMapping(55000, 55000, "tcp", "0.0.0.0"));
        for (int p = 9100; p <= 9105; p++) {
            input.add(new PortMapping(p, p, "tcp", "0.0.0.0"));
        }

        List<PortMapping> collapsed = PortMapping.collapseContiguous(input);

        assertThat(collapsed).hasSize(2);
        assertThat(collapsed).filteredOn(PortMapping::isRange)
            .singleElement()
            .satisfies(r -> {
                assertThat(r.privatePort()).isEqualTo(9100);
                assertThat(r.lastPrivatePort()).isEqualTo(9105);
            });
    }

    @Test
    void collapseContiguous_unsortedInput_stillCollapses() {
        // Order doesn't matter — collapse sorts each group internally.
        var p2 = new PortMapping(101, 101, "tcp", "0.0.0.0");
        var p1 = new PortMapping(100, 100, "tcp", "0.0.0.0");
        var p3 = new PortMapping(102, 102, "tcp", "0.0.0.0");

        List<PortMapping> collapsed = PortMapping.collapseContiguous(List.of(p2, p1, p3));

        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.get(0).privatePort()).isEqualTo(100);
        assertThat(collapsed.get(0).lastPrivatePort()).isEqualTo(102);
    }

    @Test
    void collapseContiguous_pairOfTwoIsCollapsed() {
        var a = new PortMapping(80, 80, "tcp", "0.0.0.0");
        var b = new PortMapping(81, 81, "tcp", "0.0.0.0");

        List<PortMapping> collapsed = PortMapping.collapseContiguous(List.of(a, b));

        assertThat(collapsed).hasSize(1);
        assertThat(collapsed.get(0).isRange()).isTrue();
        assertThat(collapsed.get(0).lastPrivatePort()).isEqualTo(81);
    }

    @Test
    void collapseContiguous_emptyInput_returnsEmpty() {
        assertThat(PortMapping.collapseContiguous(List.of())).isEmpty();
    }

    @Test
    void collapseContiguous_nullInput_returnsEmpty() {
        assertThat(PortMapping.collapseContiguous(null)).isEmpty();
    }
}
