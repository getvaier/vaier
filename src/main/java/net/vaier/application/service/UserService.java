package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

@Service
public class UserService implements AddUserUseCase, DeleteUserUseCase, ChangePasswordUseCase {

    private final ForPersistingUsers forPersistingUsers;

    public UserService(ForPersistingUsers forPersistingUsers) {
        this.forPersistingUsers = forPersistingUsers;
    }

    @Override
    public void addUser(String username, String password, String email, String displayname) {
        forPersistingUsers.addUser(username, password, email, displayname);
    }

    @Override
    public void deleteUser(String username) {
        forPersistingUsers.deleteUser(username);
    }

    @Override
    public void changePassword(String username, String newPassword) {
        forPersistingUsers.changePassword(username, newPassword);
    }
}
