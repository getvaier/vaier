package net.vaier.domain.port;

import net.vaier.domain.User;
import java.util.List;

/**
 * Driven query port for reading users. Mirror of {@link ForPersistingUsers}'s read side; used
 * by other domains' services that need a read-only view of the user list without coupling to
 * the inbound {@code GetUsersUseCase}.
 */
public interface ForGettingUsers {
    List<User> getUsers();
}
