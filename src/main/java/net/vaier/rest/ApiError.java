package net.vaier.rest;

/**
 * Uniform error envelope returned by {@link GlobalExceptionHandler} when a request fails
 * with an exception that reaches the global handler — an uncaught controller or framework
 * exception. It is the default error shape, not yet a universal guarantee: a few flows
 * still return their own ad-hoc shapes (the enterprise-gate 402 and the two bespoke
 * controller DTOs) pending migration. {@code code} is a stable, machine-readable token
 * (e.g. {@code BAD_REQUEST}); {@code message} is a human-readable, operator-safe
 * explanation; {@code detail} is optional extra context and is {@code null} when there
 * is nothing safe to add.
 */
public record ApiError(String code, String message, String detail) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
