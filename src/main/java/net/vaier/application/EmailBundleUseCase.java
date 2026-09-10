package net.vaier.application;

import net.vaier.domain.Operator;

/**
 * Mail the operator a link to a <b>Bundle</b> already offered, and keep the bundle a day so the link works
 * (#360). Returns the address it went to. Throws {@code NotFoundException} when the bundle is gone or its
 * hour is up, and {@code IllegalArgumentException} when nobody is signed in or mail is not set up.
 */
public interface EmailBundleUseCase {

    String email(String bundleId, Operator operator);
}
