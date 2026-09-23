package net.vaier.domain;

import net.vaier.config.ServiceNames;

/**
 * A published web route anyone on the internet can use: Vaier's sign-in is off for it, and its backend has no
 * sign-in of its own. Keyed by the route's name.
 */
public record OpenService(String routeName, String dnsName, String pathPrefix) {

    /** Whether {@code route}, as its backend was last seen, is open. Unknown is never open. */
    public static boolean isOpen(ReverseProxyRoute route, OwnSignIn lastSeen) {
        return route.hasOwnSignInToDetect() && !route.authMode().isSocial()
            && lastSeen != null && lastSeen.kind() == OwnSignIn.Kind.NONE;
    }

    /** Whether nothing can yet be said either way: public, and its backend not read. */
    static boolean isUnsettled(ReverseProxyRoute route, OwnSignIn lastSeen) {
        return route.hasOwnSignInToDetect() && !route.authMode().isSocial()
            && (lastSeen == null || lastSeen.kind() == OwnSignIn.Kind.UNKNOWN);
    }

    static OpenService of(ReverseProxyRoute route) {
        return new OpenService(route.getName(), route.getDomainName(), route.getPathPrefix());
    }

    public String subject() {
        return "[Vaier] " + dnsName + " is open to anyone";
    }

    public String body(String domain) {
        String address = "https://" + dnsName + (pathPrefix == null ? "" : pathPrefix);
        return address + " answers without any sign-in: Vaier's sign-in is off for it, and the service has "
            + "none of its own. Anyone on the internet who finds the name can use it.\n\n"
            + "To close it, open the service in the Explorer at https://" + ServiceNames.VAIER + "." + domain
            + " and press \"Put Vaier's sign-in in front\".\n\n"
            + "If it is meant to be public, press \"This is meant to be public\" there instead, and Vaier will not "
            + "mention it again.\n";
    }
}
