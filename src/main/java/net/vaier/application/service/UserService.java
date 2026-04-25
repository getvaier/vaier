package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteGroupUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.application.GetGroupsUseCase;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.UpdateUserDisplayNameUseCase;
import net.vaier.application.UpdateUserEmailUseCase;
import net.vaier.application.UpdateUserGroupsUseCase;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class UserService implements AddUserUseCase, DeleteUserUseCase, ChangePasswordUseCase,
        UpdateUserEmailUseCase, UpdateUserDisplayNameUseCase, GetUsersUseCase,
        GetGroupsUseCase, UpdateUserGroupsUseCase, DeleteGroupUseCase {

    private final ForPersistingUsers forPersistingUsers;

    public UserService(ForPersistingUsers forPersistingUsers) {
        this.forPersistingUsers = forPersistingUsers;
    }

    @Override
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @Override
    public void addUser(String username, String password, String email, String displayname, List<String> groups) {
        User.validateUsername(username);
        User.validatePassword(password);
        User.validateEmail(email);
        List<String> normalised = normaliseGroups(groups);
        forPersistingUsers.addUser(username, password, email, displayname, normalised);
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

    @Override
    public List<String> getGroups() {
        return forPersistingUsers.getUsers().stream()
                .flatMap(u -> u.getGroups() == null ? java.util.stream.Stream.<String>empty() : u.getGroups().stream())
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public void updateUserGroups(String username, List<String> groups) {
        User.validateUsername(username);
        List<String> normalised = normaliseGroups(groups);
        forPersistingUsers.setUserGroups(username, normalised);
    }

    @Override
    public void deleteGroup(String groupName) {
        User.validateGroupName(groupName);
        for (User user : forPersistingUsers.getUsers()) {
            List<String> existing = user.getGroups();
            if (existing == null || !existing.contains(groupName)) {
                continue;
            }
            List<String> remaining = existing.stream()
                    .filter(g -> !groupName.equals(g))
                    .toList();
            forPersistingUsers.setUserGroups(user.getName(), remaining);
        }
    }

    private List<String> normaliseGroups(List<String> groups) {
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
