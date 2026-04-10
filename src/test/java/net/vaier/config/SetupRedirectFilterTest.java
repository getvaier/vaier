package net.vaier.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetupRedirectFilterTest {

    @Mock SetupStateHolder setupStateHolder;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain filterChain;

    private SetupRedirectFilter filter() {
        return new SetupRedirectFilter(setupStateHolder);
    }

    @Test
    void passesThrough_whenConfigured() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(true);

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendRedirect(any());
    }

    @Test
    void redirectsToSetup_whenUnconfiguredAndNonSetupPath() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/admin.html");

        filter().doFilterInternal(request, response, filterChain);

        verify(response).sendRedirect("/setup.html");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void passesThrough_whenUnconfiguredAndSetupPath() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/setup.html");

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void passesThrough_whenUnconfiguredAndApiSetupPath() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/api/setup/status");

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void passesThrough_whenUnconfiguredAndStaticAsset() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/favicon.ico");

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void blocksSetup_whenAlreadyConfigured() throws Exception {
        when(setupStateHolder.isConfigured()).thenReturn(true);

        filter().doFilterInternal(request, response, filterChain);

        // Should pass through — controller will return 409 if already configured
        verify(filterChain).doFilter(request, response);
    }
}
