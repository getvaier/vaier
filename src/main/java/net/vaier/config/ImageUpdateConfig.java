package net.vaier.config;

import net.vaier.domain.ImageUpdateTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the update-available alert state one thing (#57 slice 3).
 *
 * <p>{@link ImageUpdateTracker} holds which images are currently <em>known</em> to be out of date, and two
 * collaborators now touch it: the daily watcher, which reports edge transitions and mails admins, and the
 * container service, whose operator-driven check clears an image's state once a pull is confirmed. They must
 * be looking at the same memory. Two instances would each hold half the truth — a check would clear a state
 * the mailer never had, and the mailer would go on believing an image was stale months after the operator
 * fixed it, silently declining to re-alert when it genuinely went stale again.
 *
 * <p>A {@code @Bean} rather than a {@code @Component} because the tracker is a domain object and the domain
 * carries no Spring annotations. Wiring is infrastructure's job, so the wiring lives here.
 */
@Configuration
public class ImageUpdateConfig {

    @Bean
    public ImageUpdateTracker imageUpdateTracker() {
        return new ImageUpdateTracker();
    }
}
