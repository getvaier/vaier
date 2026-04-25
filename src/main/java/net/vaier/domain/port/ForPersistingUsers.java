package net.vaier.domain.port;

import net.vaier.domain.User;
import java.util.List;

public interface ForPersistingUsers {
    boolean isDatabaseInitialised();
    List<User> getUsers();
    void addUser(String username, String password, String email, String displayname, List<String> groups);
    void deleteUser(String username);
    void changePassword(String username, String newPassword);
    void updateEmail(String username, String email);
    void updateDisplayName(String username, String displayname);
    void setUserGroups(String username, List<String> groups);
}
