package net.vaier.domain;

/**
 * Where one container on one machine stands, as far as Vaier has seen (#356). Vaier only ever remembers a
 * container it has actually watched run — a container that has never been seen running has no standing at
 * all, and that absence is the whole reason this can be quiet about the many containers that are stopped
 * on purpose, forever.
 *
 * <p>{@link #RUNNING} — Vaier saw it running on its last answered scrape of that machine.
 * {@link #GONE} — it was running, and two consecutive scrapes since have found it not running. Two,
 * because one is a Vaier-driven update or a restart in progress, and neither of those is news.
 *
 * <p><b>#356.</b> A machine rebooted, the containers with a restart policy came back, one with
 * {@code restart: no} did not, and Vaier reported the machine green on every axis it watched — reachable,
 * disk fine, backups green — because every one of those things was true. The information was already on
 * the wire: the 30-second scrape had seen that container running and now saw it stopped. This is the
 * verdict nobody was drawing from it.
 */
public enum ContainerStanding {
    RUNNING,
    GONE
}
