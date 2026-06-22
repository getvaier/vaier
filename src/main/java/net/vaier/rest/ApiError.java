package net.vaier.rest;

/**
 * Uniform error envelope returned by {@link GlobalExceptionHandler} for every failed
 * request. {@code code} is a stable, machine-readable token (e.g. {@code BAD_REQUEST});
 * {@code message} is a human-readable, operator-safe explanation; {@code detail} is
 * optional extra context and is {@code null} when there is nothing safe to add.
 */
public record ApiError(String code, String message, String detail) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
