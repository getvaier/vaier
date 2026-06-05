package net.vaier.rest;

import net.vaier.application.GetEditionUseCase;
import net.vaier.domain.Edition;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseLicenseInterceptorTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    static class Sample {
        @RequiresEnterprise
        public void gated() {}

        public void open() {}
    }

    private HandlerMethod handler(String method) throws Exception {
        Method m = Sample.class.getMethod(method);
        return new HandlerMethod(new Sample(), m);
    }

    private EnterpriseLicenseInterceptor interceptor(Edition edition) {
        GetEditionUseCase getEdition = () -> edition;
        return new EnterpriseLicenseInterceptor(getEdition);
    }

    @Test
    void allowsAGatedEndpointWhenEnterprise() throws Exception {
        boolean proceed = interceptor(Edition.ENTERPRISE).preHandle(request, response, handler("gated"));

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksAGatedEndpointWith402WhenCommunity() throws Exception {
        boolean proceed = interceptor(Edition.COMMUNITY).preHandle(request, response, handler("gated"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(402);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString()).contains("Enterprise licence required");
    }

    @Test
    void leavesUngatedEndpointsAloneEvenWhenCommunity() throws Exception {
        boolean proceed = interceptor(Edition.COMMUNITY).preHandle(request, response, handler("open"));

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonHandlerMethodTargets() throws Exception {
        boolean proceed = interceptor(Edition.COMMUNITY).preHandle(request, response, "not-a-handler");

        assertThat(proceed).isTrue();
    }
}
