package net.vaier.config;

import net.vaier.rest.EnterpriseLicenseInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final EnterpriseLicenseInterceptor enterpriseLicenseInterceptor;

    public WebConfig(EnterpriseLicenseInterceptor enterpriseLicenseInterceptor) {
        this.enterpriseLicenseInterceptor = enterpriseLicenseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(enterpriseLicenseInterceptor);
    }

    /**
     * Disable the default async-request timeout, so a long-but-healthy streaming download is never cut off
     * mid-stream (#321). A directory download from the Explorer streams as a zip built file-by-file over SFTP;
     * a legitimately large tree (the {@code .m2} cache is over a thousand files) streams for a while, and
     * Spring's default async timeout would abort it. By then the response is already committed with a zip
     * content-type, so the abort cannot even be reported — the browser just receives a truncated, corrupt
     * archive. {@code -1} means "no timeout". This does not affect SSE ({@code /transfers/events},
     * {@code /backup-jobs/events}, {@code /vpn/peers/events}): each {@code SseEmitter} sets its own
     * ({@code Long.MAX_VALUE}) timeout, which takes precedence over this default.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1L);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
