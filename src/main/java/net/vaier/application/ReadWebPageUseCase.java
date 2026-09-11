package net.vaier.application;

import net.vaier.domain.WebPage;

/**
 * Read one public web page, the reading half of the <b>web read</b> (#360). Whether the address is on the
 * public internet at all is the domain's decision ({@code WebAddress}), taken before anything is connected
 * and again at every redirect the page leads through.
 *
 * <p>Throws {@code IllegalArgumentException} when the address is refused or the page could not be read,
 * worded for the one who asked and never carrying more than the host.
 */
public interface ReadWebPageUseCase {

    WebPage read(String url);
}
