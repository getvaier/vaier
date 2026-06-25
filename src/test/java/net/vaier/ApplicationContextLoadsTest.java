package net.vaier;

import net.vaier.domain.port.ForExecutingInContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-context smoke test: builds the entire Spring bean graph with the real service and
 * adapter beans, and fails if the context cannot be created — e.g. an unresolvable circular
 * dependency between services.
 *
 * <p>This guards a gap the controller integration tests cannot cover: {@code VaierWebMvcIntegrationBase}
 * uses {@code @WebMvcTest} with every service replaced by a {@code @MockBean}, so service-layer
 * wiring cycles are invisible to it. #289's LAN-server delete cascade closed a real cycle —
 * {@code PublishingService -> ContainerService -> LanServerService (ForGettingLanServers) ->
 * PublishingService (DeletePublishedServiceUseCase)} — that broke application startup yet kept
 * every unit/IT green. Running in the surefire ({@code mvn test}) phase, this test fails fast on
 * any future cycle.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
class ApplicationContextLoadsTest {

    // DockerExecAdapter's @PostConstruct pings the Docker daemon and rethrows if it can't connect,
    // which would make this purely-wiring guard fail wherever Docker is absent (CI, contributors).
    // Mock its port so the real adapter isn't created; the bean cycle this test guards is entirely
    // among the service beans, so they stay real and the guard is unaffected.
    @MockBean
    private ForExecutingInContainer forExecutingInContainer;

    @Test
    void contextLoads() {
        // Passes iff Spring can instantiate and wire every bean in the application context.
    }
}
