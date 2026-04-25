package net.vaier.application;

import java.util.List;

public interface AddUserUseCase {
    void addUser(String username, String password, String email, String displayname, List<String> groups);
}
