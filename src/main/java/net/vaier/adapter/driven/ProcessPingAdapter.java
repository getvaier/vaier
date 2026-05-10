package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPingingHost;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ProcessPingAdapter implements ForPingingHost {

    // The ubuntu/debian iputils-ping package ships /bin/ping with file-cap cap_net_raw+ep,
    // so the unprivileged process can use raw sockets without any compose-side cap_add.
    private static final String PING_BIN = "/bin/ping";

    @Override
    public boolean isReachable(String host, int timeoutMs) {
        // -W is in seconds (round up). -c 1 fires a single echo; -n skips reverse DNS.
        int waitSeconds = Math.max(1, (timeoutMs + 999) / 1000);
        Process process = null;
        try {
            process = new ProcessBuilder(PING_BIN, "-c", "1", "-n", "-W", String.valueOf(waitSeconds), host)
                .redirectErrorStream(true)
                .start();
            // Give ping a small grace beyond -W so a freshly-started process has time to exit
            // cleanly even when the host is offline.
            boolean finished = process.waitFor(timeoutMs + 500L, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            log.debug("ICMP probe to {} failed to launch: {}", host, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return false;
        }
    }
}
