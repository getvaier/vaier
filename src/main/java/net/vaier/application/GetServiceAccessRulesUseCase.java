package net.vaier.application;

import java.util.List;
import java.util.Map;

public interface GetServiceAccessRulesUseCase {

    /** Every configured per-service access rule, keyed by host. Hosts with no rule are absent. */
    Map<String, List<String>> getServiceAccessRules();
}
