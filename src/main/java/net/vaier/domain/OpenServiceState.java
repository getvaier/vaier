package net.vaier.domain;

import java.util.Set;
import java.util.TreeSet;

/**
 * What Vaier remembers about open services, by route name: which ones admins were already mailed about, and
 * which ones the operator said are meant to be public. Persisted, so a redeploy never mails again.
 */
public record OpenServiceState(Set<String> notified, Set<String> meantToBePublic) {

    public OpenServiceState {
        notified = Set.copyOf(notified);
        meantToBePublic = Set.copyOf(meantToBePublic);
    }

    public static OpenServiceState empty() {
        return new OpenServiceState(Set.of(), Set.of());
    }

    public boolean isMeantToBePublic(String routeName) {
        return meantToBePublic.contains(routeName);
    }

    OpenServiceState withMeantToBePublic(String routeName, boolean meant) {
        Set<String> next = new TreeSet<>(meantToBePublic);
        if (meant) {
            next.add(routeName);
        } else {
            next.remove(routeName);
        }
        return new OpenServiceState(notified, next);
    }
}
