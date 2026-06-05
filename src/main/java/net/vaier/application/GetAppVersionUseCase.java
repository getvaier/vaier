package net.vaier.application;

/**
 * Surfaces the running Vaier build version for display, so the operator always knows which
 * version is deployed.
 */
public interface GetAppVersionUseCase {

    String appVersion();
}
