package net.vaier.application;

import net.vaier.domain.Operator;
import net.vaier.domain.ServiceCallAnswer;

/** One GET of a published service's own API, as {@code operator}: the read half of a <b>Service call</b>. */
public interface ReadServiceUseCase {

    ServiceCallAnswer readService(Operator operator, String host, String pathPrefix, String path);
}
