package net.vaier.domain.port;

import net.vaier.domain.User;
import java.util.List;

public interface ForPersistingUsers {
    List<User> getUsers();
    void addUser(String username, String password, String email, String displayname);
    void deleteUser(String username);
    void changePassword(String username, String newPassword);
}
