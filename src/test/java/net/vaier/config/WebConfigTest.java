package net.vaier.config;

import net.vaier.rest.EnterpriseLicenseInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

/**
 * The async-request configuration. A directory download streams as a zip built file-by-file over SFTP, and a
 * legitimately large tree streams for a while — Spring's default async-request timeout would abort it
 * mid-stream, after the response is already committed with a zip content-type, so the browser would receive a
 * truncated, corrupt archive (#321). The timeout must be disabled for streaming responses.
 */
class WebConfigTest {

    @Test
    void configureAsyncSupport_disablesTheDefaultAsyncTimeout_soLongStreamingDownloadsAreNotCutOff() {
        WebConfig config = new WebConfig(Mockito.mock(EnterpriseLicenseInterceptor.class));
        AsyncSupportConfigurer configurer = Mockito.mock(AsyncSupportConfigurer.class);

        config.configureAsyncSupport(configurer);

        // -1 means "no timeout": a long-but-healthy download runs to completion instead of being cut off.
        Mockito.verify(configurer).setDefaultTimeout(-1L);
    }
}
