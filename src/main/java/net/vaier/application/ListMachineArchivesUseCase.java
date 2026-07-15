package net.vaier.application;

import net.vaier.domain.Archive;

import java.util.List;

/**
 * List a machine's {@link Archive}s — the past the Explorer's time rail scrubs over. Where
 * {@link ListArchivesUseCase} lists a repository's archives, this answers the Explorer's question: given a
 * <em>machine</em>, what archives can it be browsed at? The archives come via the machine's backup job →
 * repository, newest first.
 *
 * <p>Like {@link ListArchivesUseCase} it never throws: a machine with no backup job (nothing to browse), an
 * unknown repository, an unreachable host or a failed {@code borg list} all yield an empty list.
 */
public interface ListMachineArchivesUseCase {

    /** Every archive the machine named {@code machineName} can be browsed at, newest first; empty when none. */
    List<Archive> listMachineArchives(String machineName);
}
