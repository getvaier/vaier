package net.vaier.config;

import net.vaier.application.OpenTerminalSessionUseCase;
import net.vaier.rest.TerminalWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the web-terminal WebSocket under {@code /machines/}(name){@code /terminal} (#308). The
 * machine name is pulled from the path in the handler (it may contain spaces, e.g. "Vaier server").
 *
 * <p>The upgrade is restricted to an explicit origin allowlist — the real Vaier origin
 * ({@code https://vaier.<domain>}) plus local-dev origins — so a cross-site page can never open a
 * terminal WebSocket even if a browser were to attach the auth cookie. This is a real same-origin
 * check, independent of cookie {@code SameSite} behaviour, layered on top of Traefik's forward-auth.
 */
@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final OpenTerminalSessionUseCase openTerminalSessionUseCase;
    private final ConfigResolver configResolver;

    public TerminalWebSocketConfig(OpenTerminalSessionUseCase openTerminalSessionUseCase,
                                   ConfigResolver configResolver) {
        this.openTerminalSessionUseCase = openTerminalSessionUseCase;
        this.configResolver = configResolver;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TerminalWebSocketHandler(openTerminalSessionUseCase), "/machines/*/terminal")
            .setAllowedOriginPatterns(allowedOriginPatterns(configResolver.getDomain()).toArray(String[]::new));
    }

    /**
     * The allowed WebSocket upgrade origins: {@code https://vaier.<domain>} when a domain is
     * configured, plus {@code http://localhost:*} and {@code http://127.0.0.1:*} for local dev
     * ({@code mvn spring-boot:run}). Never a match-everything {@code *} — a blank domain simply omits
     * the Vaier origin rather than falling back to allowing all.
     */
    static List<String> allowedOriginPatterns(String domain) {
        List<String> patterns = new ArrayList<>();
        if (domain != null && !domain.isBlank()) {
            patterns.add("https://vaier." + domain.trim());
        }
        patterns.add("http://localhost:*");
        patterns.add("http://127.0.0.1:*");
        return patterns;
    }
}
