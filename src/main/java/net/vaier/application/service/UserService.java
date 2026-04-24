package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.UpdateUserDisplayNameUseCase;
import net.vaier.application.UpdateUserEmailUseCase;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService implements AddUserUseCase, DeleteUserUseCase, ChangePasswordUseCase,
        UpdateUserEmailUseCase, UpdateUserDisplayNameUseCase, GetUsersUseCase {

    private final ForPersistingUsers forPersistingUsers;

    public UserService(ForPersistingUsers forPersistingUsers) {
        this.forPersistingUsers = forPersistingUsers;
    }

    @Override
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @Override
    public void addUser(String username, String password, String email, String displayname) {
        User.validateUsername(username);
        User.validatePassword(password);
        User.validateEmail(email);
        forPersistingUsers.addUser(username, password, email, displayname);
    }

    @Override
    public void deleteUser(String username) {
        User.validateUsername(username);
        forPersistingUsers.deleteUser(username);
    }

    @Override
    public void changePassword(String username, String newPassword) {
        User.validateUsername(username);
        User.validatePassword(newPassword);
        forPersistingUsers.changePassword(username, newPassword);
    }

    @Override
    public void updateEmail(String username, String email) {
        User.validateUsername(username);
        User.validateEmail(email);
        forPersistingUsers.updateEmail(username, email);
    }

    @Override
    public void updateDisplayName(String username, String displayname) {
        User.validateUsername(username);
        User.validateDisplayname(displayname);
        forPersistingUsers.updateDisplayName(username, displayname);
    }
}
