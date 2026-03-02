package net.vaier.domain;

import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import java.util.Optional;

public class Lifecycle {

    private final ForInitialisingUserService forInitialisingUserService;
    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers containerRestarter;

    public Lifecycle(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForRestartingContainers containerRestarter
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.containerRestarter = containerRestarter;
    }

    public void start() {
        forInitialisingUserService.initialiseConfiguration();

        Optional<User> admin = forPersistingUsers.getUsers().stream()
            .filter(user -> user.getName().equals("admin"))
            .findFirst();
        if(admin.isEmpty()) {
            forPersistingUsers.addUser("admin", "admin", "", "Admin");
        }

        containerRestarter.restartContainer("authelia");
    }
}
