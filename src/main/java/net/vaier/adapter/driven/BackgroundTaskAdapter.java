package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForRunningInBackground;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Spring's async executor: the caller returns at once, and a failure ends in the log. */
@Component
@Slf4j
public class BackgroundTaskAdapter implements ForRunningInBackground {

    @Async
    @Override
    public void run(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("A background task failed: {}", e.getMessage(), e);
        }
    }
}
