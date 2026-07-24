package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskUsageTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    /**
     * The real {@code df -P} from the NAS (#325). Everything that makes the old {@code df -P /} reading a
     * lie is in here: {@code /} is the 2.3 GB DSM system partition, pinned at 88% by design; {@code /volume1}
     * is the 11.6 TB volume that holds every borg backup, 39% used with 7.1 TB free — and Vaier could not
     * see it at all. The eight {@code none} rows are Docker's aufs driver, all aliases of {@code /volume1}.
     */
    private static final String NAS_DF =
        "Filesystem             1024-blocks       Used  Available Capacity Mounted on\n"
            + "/dev/md0                   2385528    1988940     277804      88% /\n"
            + "devtmpfs                   2017160          0    2017160       0% /dev\n"
            + "tmpfs                      2021044       2528    2018516       1% /dev/shm\n"
            + "tmpfs                      2021044      28340    1992704       2% /run\n"
            + "tmpfs                      2021044          0    2021044       0% /sys/fs/cgroup\n"
            + "tmpfs                      2021044       1988    2019056       1% /tmp\n"
            + "/dev/mapper/cachedev_0   115404288     512932  114875740       1% /volume2\n"
            + "/dev/mapper/cachedev_1 11614435576 4494352836 7119963956      39% /volume1\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/b5720e8\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/1e756f0\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/2a1c9d3\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/3b2d0e4\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/4c3e1f5\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/5d4f206\n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/6e5031 \n"
            + "none                   11614435576 4494352836 7119963956      39% /volume1/@docker/aufs/mnt/7f6142a\n";

    /** A plain Linux host: one real root filesystem, plus the usual kernel mounts. */
    private static final String LINUX_DF =
        "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
            + "/dev/root         61255492 55129943   6125549      90% /\n"
            + "tmpfs               976284        0    976284       0% /dev/shm\n"
            + "/dev/nvme0n1p15     106858     6182    100676       6% /boot/efi\n";

    // --- reading every real filesystem (#325) -----------------------------------------------------------
    //
    // The bug: DF_COMMAND was `df -P /`, so Vaier watched the root filesystem and only the root filesystem.
    // On the NAS that is the 2.3 GB DSM system partition — 88% by design, never moves — while /volume1, the
    // 11.6 TB volume holding every borg backup, was invisible. It could have filled to 100% in silence.

    @Test
    void dfCommand_readsEveryFilesystem_notJustRoot() {
        assertThat(RemoteDiskUsage.DF_COMMAND).isEqualTo("df -P");
    }

    @Test
    void parseList_readsEveryRealFilesystem_onTheNas() {
        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList("NAS", NAS_DF);

        assertThat(filesystems).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/volume2", "/volume1");
    }

    @Test
    void parseList_seesTheVolumeThatMatters_withItsSizeAndItsFreeSpace() {
        // The whole point of #325: /volume1 is 39% used with 7.1 TB free, and Vaier reported 88% for this
        // machine. A percentage alone could not have told the operator that — the size has to travel too.
        RemoteDiskUsage volume1 = RemoteDiskUsage.parseList("NAS", NAS_DF).stream()
            .filter(fs -> fs.mountPoint().equals("/volume1"))
            .findFirst().orElseThrow();

        assertThat(volume1.device()).isEqualTo("/dev/mapper/cachedev_1");
        assertThat(volume1.usedPercent()).isEqualTo(39);
        assertThat(volume1.sizeKb()).isEqualTo(11614435576L);
        assertThat(volume1.usedKb()).isEqualTo(4494352836L);
        assertThat(volume1.availableKb()).isEqualTo(7119963956L);
    }

    @Test
    void parseList_dropsThePseudoFilesystems_andTheAufsAliasesOfVolume1() {
        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList("NAS", NAS_DF);

        assertThat(filesystems).extracting(RemoteDiskUsage::device)
            .doesNotContain("none", "tmpfs", "devtmpfs");
        assertThat(filesystems).extracting(RemoteDiskUsage::mountPoint)
            .noneMatch(mount -> mount.startsWith("/volume1/@docker"));
        assertThat(filesystems).hasSize(3);          // /, /volume2, /volume1 — the eight aliases are gone
    }

    @Test
    void parseList_onAPlainLinuxHost_keepsRootAndTheRealPartitions() {
        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList("Apalveien 5", LINUX_DF);

        assertThat(filesystems).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/boot/efi");
    }

    // --- parse stays total ------------------------------------------------------------------------------
    //
    // Exactly as it has always been, and as Archive.parseList is: bad input is an empty list, never an
    // exception. An unreachable host, a df that failed, a kernel that speaks something else — the caller
    // treats an empty reading as "cannot tell", never as "disk full".

    @Test
    void parseList_blankOrNull_returnsEmpty() {
        assertThat(RemoteDiskUsage.parseList("nas", null)).isEmpty();
        assertThat(RemoteDiskUsage.parseList("nas", "   ")).isEmpty();
    }

    @Test
    void parseList_headerOnly_returnsEmpty() {
        assertThat(RemoteDiskUsage.parseList("nas",
            "Filesystem 1024-blocks Used Available Capacity Mounted on")).isEmpty();
    }

    @Test
    void parseList_garbage_returnsEmpty_neverThrows() {
        assertThat(RemoteDiskUsage.parseList("nas", "bash: df: command not found")).isEmpty();
        assertThat(RemoteDiskUsage.parseList("nas", "%%% \n \t ??? \n")).isEmpty();
    }

    @Test
    void parseList_skipsAMalformedRow_butKeepsTheGoodOnes() {
        String mixed = "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
            + "/dev/root         61255492 55129943   6125549      90% /\n"
            + "/dev/broken            wat      wat       wat     wat% /broken\n"
            + "/dev/sdb1          1000000   500000    500000      50% /data\n";

        assertThat(RemoteDiskUsage.parseList("nas", mixed)).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/data");
    }

    @Test
    void parseList_keepsAMountPointContainingSpaces() {
        // The mount point is the last column and may contain spaces; everything before it is fixed-width.
        String withSpace = "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
            + "/dev/sdb1          1000000   500000    500000      50% /mnt/my disk\n";

        assertThat(RemoteDiskUsage.parseList("nas", withSpace)).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/mnt/my disk");
    }

    // --- the breach verdict: mute, own threshold, global fallback ---------------------------------------
    //
    // One decision, in the domain, for the alert email and the Explorer alike. That the two can never
    // disagree is the whole reason DF_COMMAND lives here — and now the verdict does too.

    @Test
    void isAbove_isStrictlyGreater() {
        assertThat(fs("/", 85).isAbove(85)).isFalse();
        assertThat(fs("/", 85).isAbove(84)).isTrue();
    }

    @Test
    void breaches_withNoWatchOfItsOwn_fallsBackToTheGlobalThreshold() {
        DiskWatch defaulted = DiskWatch.watchedByDefault(mid("NAS"), "/volume1");

        assertThat(fs("/volume1", 90).breaches(defaulted, 85)).isTrue();
        assertThat(fs("/volume1", 39).breaches(defaulted, 85)).isFalse();
    }

    @Test
    void breaches_withItsOwnThreshold_ignoresTheGlobalOne() {
        // The NAS's / sits at 88% by design and would page forever at the global 85%. Given its own 95%
        // threshold it is quiet — and still watched, so a genuinely full system partition still speaks.
        DiskWatch ownThreshold = new DiskWatch(mid("NAS"), "/", true, 95);

        assertThat(fs("/", 88).breaches(ownThreshold, 85)).isFalse();
        assertThat(fs("/", 96).breaches(ownThreshold, 85)).isTrue();
    }

    @Test
    void breaches_whenMuted_isNeverABreach_howeverFull() {
        DiskWatch muted = new DiskWatch(mid("NAS"), "/", false, null);

        assertThat(fs("/", 100).breaches(muted, 85)).isFalse();
    }

    @Test
    void effectiveThreshold_isTheFilesystemsOwn_orTheGlobalOne() {
        assertThat(fs("/", 50).effectiveThreshold(new DiskWatch(mid("NAS"), "/", true, 95), 85)).isEqualTo(95);
        assertThat(fs("/", 50).effectiveThreshold(DiskWatch.watchedByDefault(mid("NAS"), "/"), 85)).isEqualTo(85);
    }

    // --- one verdict, asked once ------------------------------------------------------------------------
    //
    // The alert email and the Explorer must never be able to disagree about a disk, and the only way to
    // guarantee that is for neither of them to decide. `judge` is the single call both make: it resolves
    // mute, the filesystem's own threshold and the global fallback, and hands back the answer whole.

    @Test
    void judge_resolvesMuteThresholdAndFallback_inOneAnswer() {
        RemoteDiskUsage volume1 = fs("/volume1", 91);

        var watched = volume1.judge(DiskWatch.watchedByDefault(mid("NAS"), "/volume1"), 85);
        assertThat(watched.watched()).isTrue();
        assertThat(watched.thresholdPercent()).isEqualTo(85);
        assertThat(watched.breaching()).isTrue();
        assertThat(watched.silent()).isFalse();

        var ownThreshold = fs("/", 88).judge(new DiskWatch(mid("NAS"), "/", true, 95), 85);
        assertThat(ownThreshold.thresholdPercent()).isEqualTo(95);
        assertThat(ownThreshold.breaching()).isFalse();      // 88% is normal on a DSM system partition
    }

    @Test
    void judge_aMutedFilesystem_isSilent_soVaierSaysNothingAboutItAtAll() {
        // Silent is stronger than "does not breach": a muted filesystem raises no level alert AND no fill
        // forecast. Muting means "do not speak about this disk", and an early-warning email is speaking.
        var verdict = fs("/", 100).judge(new DiskWatch(mid("NAS"), "/", false, null), 85);

        assertThat(verdict.watched()).isFalse();
        assertThat(verdict.silent()).isTrue();
        assertThat(verdict.breaching()).isFalse();
    }

    // --- what the alert actually says -------------------------------------------------------------------
    //
    // "NAS is at 88%" told the operator nothing they could act on — they checked DSM and found the disk
    // nowhere near full, and rightly stopped trusting Vaier. The mount and the size have to be in the words.

    @Test
    void pressureSubject_namesTheMountNotJustTheMachine() {
        RemoteDiskUsage volume1 = new RemoteDiskUsage("NAS", "/dev/mapper/cachedev_1", "/volume1",
            11614435576L, 10614435576L, 1000000000L, 91);

        assertThat(volume1.pressureSubject())
            .contains("NAS").contains("/volume1").contains("91%");
    }

    @Test
    void pressureSubject_carriesTheSizeAndTheFreeSpace_soTheNumberMeansSomething() {
        RemoteDiskUsage volume1 = new RemoteDiskUsage("NAS", "/dev/mapper/cachedev_1", "/volume1",
            11614435576L, 10614435576L, 1000000000L, 91);

        assertThat(volume1.pressureSubject()).contains("TiB");
        assertThat(volume1.sizeHuman()).isEqualTo("10.8 TiB");
        assertThat(volume1.availableHuman()).isEqualTo("953.7 GiB");
    }

    @Test
    void recoverySubject_namesTheMachineTheMountAndThePercent() {
        assertThat(fs("/volume1", 40).recoverySubject())
            .contains("NAS").contains("/volume1").contains("40%");
    }

    @Test
    void pressureBody_reportsTheMountItWatched_notAHardcodedSlash() {
        String body = fs("/volume1", 91).pressureBody(85, "example.com");

        assertThat(body).contains("NAS").contains("/volume1").contains("91%").contains("85%")
            .contains("https://");
    }

    @Test
    void humanSize_scalesFromBytesToTebibytes() {
        assertThat(fsSized(0L).sizeHuman()).isEqualTo("0 B");
        assertThat(fsSized(100L).sizeHuman()).isEqualTo("100.0 KiB");
        assertThat(fsSized(2385528L).sizeHuman()).isEqualTo("2.3 GiB");
        assertThat(fsSized(115404288L).sizeHuman()).isEqualTo("110.1 GiB");
    }

    /** A filesystem on the NAS at {@code usedPercent}, sized so the size never dominates an assertion. */
    private static RemoteDiskUsage fs(String mountPoint, int usedPercent) {
        return new RemoteDiskUsage("NAS", "/dev/sda1", mountPoint, 1000000L, 10000L * usedPercent,
            1000000L - 10000L * usedPercent, usedPercent);
    }

    private static RemoteDiskUsage fsSized(long sizeKb) {
        return new RemoteDiskUsage("NAS", "/dev/sda1", "/", sizeKb, 0L, sizeKb, 0);
    }
}
