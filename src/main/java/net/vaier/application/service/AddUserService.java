package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import org.springframework.stereotype.Service;

@Service
public class AddUserService implements AddUserUseCase {

    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers forRestartingContainers;

    public AddUserService(ForPersistingUsers forPersistingUsers, ForRestartingContainers forRestartingContainers) {
        this.forPersistingUsers = forPersistingUsers;
        this.forRestartingContainers = forRestartingContainers;
    }

    @Override
    public void addUser(String username, String password, String email, String displayname) {
        forPersistingUsers.addUser(username, password, email, displayname);
        forRestartingContainers.restartContainer(ServiceNames.AUTHELIA);
    }
}
