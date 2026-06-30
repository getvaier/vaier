package net.vaier.application;

import java.util.List;

public interface AssignGroupsUseCase {

    /** Set the access groups for {@code email}, preserving its existing role. */
    void assignGroups(String email, List<String> groups);
}
