package net.vaier.application;

import net.vaier.domain.Role;

public interface GrantRoleUseCase {

    /** Set the role for {@code email}, preserving its existing groups. */
    void grantRole(String email, Role role);
}
