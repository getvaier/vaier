package net.vaier.application;

import net.vaier.domain.Operator;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;

/**
 * <b>Always allow</b>: run a GET the operator said yes to, as {@code operator}, and save its path as one of
 * the service's <b>free reads</b>. A write is refused, and nothing is saved or sent.
 */
public interface AlwaysAllowServiceCallUseCase {

    ServiceCallAnswer alwaysAllow(Operator operator, String host, String pathPrefix, ServiceCall call);
}
