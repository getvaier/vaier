package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Whoever handed the task over has already moved on, so a failure is logged here and goes no further. */
class BackgroundTaskAdapterTest {

    @Test
    void run_runsTheTask_andKeepsItsFailureToItself() {
        AtomicBoolean ran = new AtomicBoolean();
        new BackgroundTaskAdapter().run(() -> ran.set(true));
        assertThat(ran).isTrue();

        assertThatCode(() -> new BackgroundTaskAdapter().run(() -> { throw new IllegalStateException("docker down"); }))
            .doesNotThrowAnyException();
    }
}
