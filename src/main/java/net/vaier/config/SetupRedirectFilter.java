package net.vaier.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SetupRedirectFilter extends OncePerRequestFilter {

    private final SetupStateHolder setupStateHolder;

    public SetupRedirectFilter(SetupStateHolder setupStateHolder) {
        this.setupStateHolder = setupStateHolder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (setupStateHolder.isConfigured() || isAllowedPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendRedirect("/setup.html");
    }

    private boolean isAllowedPath(String uri) {
        return uri.equals("/setup.html")
            || uri.startsWith("/api/setup/")
            || uri.endsWith(".css")
            || uri.endsWith(".js")
            || uri.endsWith(".ico")
            || uri.endsWith(".png")
            || uri.endsWith(".svg");
    }
}
