package net.vaier.application;

import java.util.List;

public interface UpdateUserGroupsUseCase {
    void updateUserGroups(String username, List<String> groups);
}
