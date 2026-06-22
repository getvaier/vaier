package net.vaier.rest;

/**
 * Uniform error envelope for failed requests. Most failures reach it via
 * {@link GlobalExceptionHandler} (any uncaught controller or framework exception);
 * {@code SettingsRestController} also emits it directly, where bad AWS/SMTP credentials
 * are deliberately mapped to {@code 400} rather than the generic {@code 500} the handler
 * would give a raw SDK exception. The one remaining non-{@code ApiError} failure is the
 * enterprise-gate {@code 402}. {@code code} is a stable, machine-readable token
 * (e.g. {@code BAD_REQUEST}); {@code message} is a human-readable, operator-safe
 * explanation; {@code detail} is optional extra context and is {@code null} when there
 * is nothing safe to add.
 */
public record ApiError(String code, String message, String detail) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
