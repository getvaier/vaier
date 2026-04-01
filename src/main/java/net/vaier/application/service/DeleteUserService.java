package net.vaier.application.service;

import net.vaier.application.DeleteUserUseCase;
import net.vaier.config.ServiceNames;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import org.springframework.stereotype.Service;

@Service
public class DeleteUserService implements DeleteUserUseCase {

    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers forRestartingContainers;

    public DeleteUserService(ForPersistingUsers forPersistingUsers, ForRestartingContainers forRestartingContainers) {
        this.forPersistingUsers = forPersistingUsers;
        this.forRestartingContainers = forRestartingContainers;
    }

    @Override
    public void deleteUser(String username) {
        forPersistingUsers.deleteUser(username);
        forRestartingContainers.restartContainer(ServiceNames.AUTHELIA);
    }
}
