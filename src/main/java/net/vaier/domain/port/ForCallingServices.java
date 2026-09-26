package net.vaier.domain.port;

import net.vaier.domain.ServiceCall;
import net.vaier.domain.ServiceCallAnswer;

/**
 * Driven port: one <b>Service call</b> to a published service's backend, redirects not followed.
 * {@code authorization} is the header value to send, or null for none.
 */
public interface ForCallingServices {

    ServiceCallAnswer call(String url, ServiceCall call, String authorization);
}
