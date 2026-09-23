package net.vaier.domain;

/** Whether new sign-in settings reached Dex and oauth2-proxy, in words the operator can act on. */
public record SignInApplyOutcome(boolean applied, String message) {

    public static SignInApplyOutcome succeeded() {
        return new SignInApplyOutcome(true, "Applied. Dex and the sign-in page have restarted with it.");
    }

    public static SignInApplyOutcome failed(String renderer, String detail) {
        return new SignInApplyOutcome(false, renderer + " could not render it, so sign-in keeps running on "
            + "the settings it had, and those were put back: " + detail);
    }
}
