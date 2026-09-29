package net.vaier.application;

import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;

/** A write to a published service's own API, carrying Marvin's service credential, once the operator said yes. */
public interface CallServiceUseCase {

    ServiceCallAnswer callService(String host, String pathPrefix, ServiceCall call);
}
