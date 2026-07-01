package net.vaier.application;

import java.util.List;

public interface SetServiceAccessRuleUseCase {

    /**
     * Set the access rule for a published service {@code host}: the <em>any-of</em> list of groups an
     * identity may satisfy to reach it. An empty (or all-blank) list clears the rule, meaning any
     * authenticated, approved user may reach the service.
     */
    void setAllowedGroups(String host, List<String> groups);
}
