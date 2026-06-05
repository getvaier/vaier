package net.vaier.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller class or handler method as an Enterprise-only endpoint. Requests reach it
 * only when the running {@link net.vaier.domain.Edition} is
 * {@link net.vaier.domain.Edition#ENTERPRISE}; otherwise {@link EnterpriseLicenseInterceptor}
 * short-circuits with {@code 402 Payment Required}. Placing it on the class gates every handler in
 * that controller.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresEnterprise {
}
