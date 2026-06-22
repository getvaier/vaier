package net.vaier.rest;

import net.vaier.domain.NotFoundException;
import net.vaier.domain.ConflictException;

/**
 * Uniform error envelope for failed requests. Most failures reach it via
 * {@link GlobalExceptionHandler} (any uncaught controller or framework exception);
 * {@code SettingsRestController} also emits it directly, where bad AWS/SMTP credentials
 * are deliberately mapped to {@code 400} rather than the generic {@code 500} the handler
 * would give a raw SDK exception. Validation ({@code 400}), not-found ({@code 404}, via
 * {@link NotFoundException}) and conflict ({@code 409}, via
 * {@link ConflictException}) failures all render in this envelope. The
 * only error responses that aren't an {@code ApiError} are the enterprise-gate {@code 402}
 * and the body-less {@code 404}s for missing optional GET artifacts (favicon, an
 * already-retrieved one-shot config). {@code code} is a stable, machine-readable token
 * (e.g. {@code BAD_REQUEST}); {@code message} is a human-readable, operator-safe
 * explanation; {@code detail} is optional extra context and is {@code null} when there
 * is nothing safe to add.
 */
public record ApiError(String code, String message, String detail) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
