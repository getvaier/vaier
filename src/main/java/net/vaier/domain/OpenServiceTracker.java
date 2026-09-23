package net.vaier.domain;

import net.vaier.domain.port.ForPersistingOpenServiceState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which open services admins should hear about now: each one once, when first found. The latch is held
 * while the service stays open — and while nothing can be said, because unknown is not fixed — and dropped
 * silently once it is really closed or gone, so opening it again is news again. A service meant to be public
 * is never news.
 */
public class OpenServiceTracker {

    private final ForPersistingOpenServiceState states;

    public OpenServiceTracker(ForPersistingOpenServiceState states) {
        this.states = states;
    }

    /** Record this round's reading and return the open services to mail about. */
    public synchronized List<OpenService> observe(List<ReverseProxyRoute> routes, Map<String, OwnSignIn> lastSeen) {
        OpenServiceState before = states.read();
        Set<String> present = new TreeSet<>();
        Set<String> notified = new TreeSet<>();
        List<OpenService> news = new ArrayList<>();
        for (ReverseProxyRoute route : routes) {
            if (!route.hasOwnSignInToDetect()) {
                continue;
            }
            String name = route.getName();
            present.add(name);
            OwnSignIn seen = lastSeen.get(name);
            boolean open = OpenService.isOpen(route, seen);
            if (open && !before.notified().contains(name) && !before.isMeantToBePublic(name)) {
                news.add(OpenService.of(route));
                notified.add(name);
            } else if (before.notified().contains(name) && (open || OpenService.isUnsettled(route, seen))) {
                notified.add(name);
            }
        }
        Set<String> meant = new TreeSet<>(before.meantToBePublic());
        meant.retainAll(present);
        OpenServiceState after = new OpenServiceState(notified, meant);
        if (!after.equals(before)) {
            states.save(after);
        }
        return news;
    }

    /** The operator's word that a route is (or is no longer) meant to be public. */
    public synchronized void meantToBePublic(String routeName, boolean meant) {
        states.save(states.read().withMeantToBePublic(routeName, meant));
    }
}
