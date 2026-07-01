package net.vaier.domain.port;

import net.vaier.domain.User;
import java.util.List;

/**
 * Driven query port for reading users. Mirror of {@link ForPersistingUsers}'s read side; retained
 * for a read-only view of the legacy Authelia user list. No inbound use case consumes it any more
 * (the user-management surface was removed); kept, like the rest of the Authelia backend, for a
 * later cleanup pass.
 */
public interface ForGettingUsers {
    List<User> getUsers();
}
