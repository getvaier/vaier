package net.vaier.application;

import net.vaier.application.DownloadFileUseCase.Download;

/**
 * Open an offered <b>Bundle</b> for download: the Explorer's selection zip, under the bundle's own name
 * (#360). Throws {@code NotFoundException} when the bundle is gone or its hour is up.
 */
public interface OpenBundleUseCase {

    Download open(String id);
}
