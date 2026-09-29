package net.vaier.application;

import net.vaier.domain.ServiceCallAnswer;

/** One GET of a published service's own API, carrying Marvin's service credential: the read half of a <b>Service call</b>. */
public interface ReadServiceUseCase {

    ServiceCallAnswer readService(String host, String pathPrefix, String path);
}
