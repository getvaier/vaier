package net.vaier.application;

import net.vaier.domain.Operator;
import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;

/** A write to a published service's own API, as {@code operator}, once they have said yes to it. */
public interface CallServiceUseCase {

    ServiceCallAnswer callService(Operator operator, String host, String pathPrefix, ServiceCall call);
}
