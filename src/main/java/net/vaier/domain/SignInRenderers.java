package net.vaier.domain;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPersistingSignInSettings;
import net.vaier.domain.port.ForRerunningContainers;
import net.vaier.domain.port.ForRestartingContainers;

import java.util.List;

/**
 * The one-shot containers that render the sign-in chain's config — dex-init for Dex, oauth2-proxy-init for
 * oauth2-proxy's sign-in page — and the rule for applying new sign-in settings through them from inside
 * the stack those settings gate: a service is restarted only onto a config its renderer finished, and a
 * renderer that fails puts the previous settings back, so the next boot renders what still works.
 */
@Slf4j
public final class SignInRenderers {

    private record Renderer(String container, String renders) {}

    // Dex first: a sign-in button for a connector Dex does not have yet is a Bad Request.
    private static final List<Renderer> IN_ORDER = List.of(
        new Renderer("dex-init", "dex"),
        new Renderer("oauth2-proxy-init", "oauth2-proxy"));

    private SignInRenderers() {}

    public static SignInApplyOutcome apply(SignInSettings before, SignInSettings after,
                                           ForPersistingSignInSettings store,
                                           ForRerunningContainers reruns, ForRestartingContainers restarts) {
        store.save(after);
        SignInApplyOutcome outcome = render(reruns, restarts);
        if (!outcome.applied()) {
            store.save(before);
            SignInApplyOutcome restored = render(reruns, restarts);
            if (!restored.applied()) {
                log.error("Re-rendering the previous sign-in settings failed too: {}", restored.message());
            }
        }
        return outcome;
    }

    private static SignInApplyOutcome render(ForRerunningContainers reruns, ForRestartingContainers restarts) {
        for (Renderer renderer : IN_ORDER) {
            try {
                ContainerRun run = reruns.rerun(renderer.container());
                if (!run.succeeded()) {
                    return SignInApplyOutcome.failed(renderer.container(),
                        "exit " + run.exitCode() + (run.output().isBlank() ? "" : " — " + run.output().strip()));
                }
                restarts.restartContainer(renderer.renders());
            } catch (RuntimeException e) {
                return SignInApplyOutcome.failed(renderer.container(), String.valueOf(e.getMessage()));
            }
        }
        return SignInApplyOutcome.succeeded();
    }
}
