package net.vaier.domain;

import net.vaier.domain.port.ForPersistingSignInSettings;
import net.vaier.domain.port.ForRerunningContainers;
import net.vaier.domain.port.ForRestartingContainers;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;

import static net.vaier.domain.IdentityProvider.GOOGLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Applying sign-in settings from inside the stack they gate: each renderer re-renders its service's
 * config, and a service is restarted only onto a config its renderer finished. A renderer that fails
 * leaves its running service on the config that already works, and the settings go back to what they were.
 */
class SignInRenderersTest {

    private static final SignInSettings BEFORE = SignInSettings.none();
    private static final SignInSettings AFTER =
        new SignInSettings(Map.of(GOOGLE, new ProviderCredentials("id", "secret")), true);

    private final ForPersistingSignInSettings store = mock(ForPersistingSignInSettings.class);
    private final ForRerunningContainers reruns = mock(ForRerunningContainers.class);
    private final ForRestartingContainers restarts = mock(ForRestartingContainers.class);

    @Test
    void apply_writesTheSettings_thenRerendersAndRestartsDexBeforeOauth2Proxy() {
        when(reruns.rerun(anyString())).thenReturn(new ContainerRun(0, ""));

        SignInApplyOutcome outcome = SignInRenderers.apply(BEFORE, AFTER, store, reruns, restarts);

        assertThat(outcome.applied()).isTrue();
        InOrder order = inOrder(store, reruns, restarts);
        order.verify(store).save(AFTER);
        order.verify(reruns).rerun("dex-init");
        order.verify(restarts).restartContainer("dex");
        order.verify(reruns).rerun("oauth2-proxy-init");
        order.verify(restarts).restartContainer("oauth2-proxy");
        verify(store, never()).save(BEFORE);
    }

    @Test
    void apply_aFailedRenderer_restartsNothingOntoIt_andPutsThePreviousSettingsBack() {
        when(reruns.rerun("dex-init"))
            .thenReturn(new ContainerRun(1, "dex-init: apk: network unreachable"))
            .thenReturn(new ContainerRun(0, ""));
        when(reruns.rerun("oauth2-proxy-init")).thenReturn(new ContainerRun(0, ""));

        SignInApplyOutcome outcome = SignInRenderers.apply(BEFORE, AFTER, store, reruns, restarts);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.message()).contains("dex-init").contains("apk: network unreachable");
        InOrder order = inOrder(store, reruns, restarts);
        order.verify(store).save(AFTER);
        order.verify(reruns).rerun("dex-init");
        order.verify(store).save(BEFORE);
        order.verify(reruns).rerun("dex-init");
    }

    @Test
    void apply_dockerRefusingTheRerun_isAFailedOutcome_notAnException() {
        when(reruns.rerun(anyString())).thenThrow(new IllegalStateException("403 from docker-proxy"));

        SignInApplyOutcome outcome = SignInRenderers.apply(BEFORE, AFTER, store, reruns, restarts);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.message()).contains("403 from docker-proxy");
        verify(restarts, never()).restartContainer(anyString());
    }
}
