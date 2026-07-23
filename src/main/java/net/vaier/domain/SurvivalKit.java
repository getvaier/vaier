package net.vaier.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.vaier.domain.port.ForEncryptingSurvivalKits;

/**
 * The file that outlives the Vaier server: {@link RecoverySheet}'s contents, encrypted with one passphrase
 * only the operator knows, written to hosts Vaier picks for separation.
 *
 * <p><b>Why not a printed page.</b> The first design was a sheet of paper. It was rejected for one reason: it
 * goes stale the moment a passphrase changes — which has already orphaned a repository here once — and a
 * stale sheet is worse than none, because you believe you are covered. A kit is rewritten whenever the fleet
 * or a passphrase changes, so what the operator keeps shrinks from a list that rots to one passphrase that
 * does not.
 *
 * <p><b>Why encrypted, when the sheet was deliberately in the clear.</b> Because the copies now live on
 * machines rather than in a drawer. Plaintext copies on N hosts would mean compromising any one of them hands
 * over every key to every backup in the fleet.
 *
 * <p><b>Why the instructions are not.</b> The header is plaintext and says exactly how to open the file,
 * because a kit that needed Vaier to read it would restore the circularity the kit exists to break. The
 * instructions are worth nothing without the passphrase, and everything with it.
 *
 * <p><b>What it has to survive.</b> Only the Vaier server. The archives themselves exist in exactly one place
 * — the backup server — so if that is gone, no key helps and none of this matters. The backup server is
 * therefore a perfectly good place to keep a copy of the kit, not a conflict of interest: the scenario this
 * is written for is "Vaier is gone, the NAS is fine".
 */
public final class SurvivalKit {

    /** What the kit is called wherever it lands. The printed command names it, so the two must agree. */
    public static final String FILE_NAME = "vaier-survival-kit.txt";

    /** Everything after this line is ciphertext. The printed command strips through it and pipes the rest. */
    public static final String BEGIN_MARKER = "-----BEGIN VAIER SURVIVAL KIT-----";

    private static final DateTimeFormatter WRITTEN_AT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private SurvivalKit() {}

    /**
     * The command that opens a kit, exactly as it is printed on the kit and exactly as it must be typed.
     *
     * <p>{@code sed} rather than a line count because the header is prose that will be reworded; deleting
     * through the marker survives that, and an operator who adds a note of their own to the top does not
     * silently break their own recovery. Both tools are on every Linux and every mac.
     */
    public static String decryptCommand() {
        return "sed '1,/^" + BEGIN_MARKER + "$/d' " + FILE_NAME
            + " | openssl enc -aes-256-cbc -pbkdf2 -iter "
            + ForEncryptingSurvivalKits.PBKDF2_ITERATIONS + " -d -a";
    }

    /**
     * Render the whole kit: plaintext instructions, the marker, then the encrypted {@link RecoverySheet}.
     *
     * @param passphrase the operator's kit passphrase; blank is refused rather than defaulted, because a kit
     *                   written without one looks exactly like a protected kit and hands the fleet to anyone
     *                   who opens it
     */
    public static String render(BackupServer server, List<BackupRepository> repositories,
                                List<BackupJob> jobs, String configKey, String passphrase,
                                Instant writtenAt, ForEncryptingSurvivalKits cipher) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalArgumentException(
                "A survival kit cannot be written without a passphrase — an unprotected kit is indistinguishable "
                    + "from a protected one and gives away every backup in the fleet");
        }

        String sheet = RecoverySheet.render(server, repositories, jobs, configKey);

        StringBuilder sb = new StringBuilder();
        sb.append("VAIER SURVIVAL KIT\n");
        sb.append("==================\n\n");
        sb.append("Everything needed to read this fleet's backups without Vaier: where the archives are, and\n");
        sb.append("the passphrases that open them. It is encrypted with a passphrase only you know — Vaier\n");
        sb.append("cannot be asked for it, and neither can anyone holding this file.\n\n");
        sb.append("To read it, on any machine with openssl (every Linux and every Mac has it):\n\n");
        sb.append("  ").append(decryptCommand()).append("\n\n");
        sb.append("It will ask for the passphrase, and print the contents. Nothing is written to disk unless\n");
        sb.append("you redirect it somewhere — and if you do, delete it afterwards.\n\n");
        // Staleness is the failure this design exists to prevent, so it must be readable without the
        // passphrase, on a machine that no longer has a Vaier to ask how old its kit is.
        sb.append("Written ").append(WRITTEN_AT.format(writtenAt)).append(" by Vaier, covering ")
            .append(coverage(repositories)).append(".\n");
        sb.append("A kit much older than the fleet it protects is a kit that stopped being updated.\n\n");
        sb.append(BEGIN_MARKER).append("\n");
        sb.append(cipher.encrypt(sheet, passphrase)).append("\n");
        return sb.toString();
    }

    /**
     * How much the kit covers — counts only. Naming the repositories would put a plaintext inventory of the
     * fleet's machines on a machine in that fleet; a count is all the operator needs to recognise a kit
     * written before half of it existed.
     */
    private static String coverage(List<BackupRepository> repositories) {
        int count = repositories == null ? 0 : repositories.size();
        return count == 1 ? "1 repository" : count + " repositories";
    }
}
