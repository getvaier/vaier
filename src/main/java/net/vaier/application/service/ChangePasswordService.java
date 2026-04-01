package net.vaier.application.service;

import net.vaier.application.ChangePasswordUseCase;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import org.springframework.stereotype.Service;

@Service
public class ChangePasswordService implements ChangePasswordUseCase {

    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers forRestartingContainers;

    public ChangePasswordService(ForPersistingUsers forPersistingUsers, ForRestartingContainers forRestartingContainers) {
        this.forPersistingUsers = forPersistingUsers;
        this.forRestartingContainers = forRestartingContainers;
    }

    @Override
    public void changePassword(String username, String newPassword) {
        forPersistingUsers.changePassword(username, newPassword);
        forRestartingContainers.restartContainer("authelia");
    }
}
