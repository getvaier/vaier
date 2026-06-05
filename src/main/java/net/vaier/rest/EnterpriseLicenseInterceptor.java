package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.vaier.application.GetEditionUseCase;
import net.vaier.domain.Edition;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Enforces the Enterprise gate. Any handler (or controller) annotated {@link RequiresEnterprise}
 * is reachable only while the instance runs as {@link Edition#ENTERPRISE}; otherwise the request is
 * answered with {@code 402 Payment Required} and a small JSON body, never reaching the controller.
 * Endpoints without the annotation are untouched.
 */
@Component
public class EnterpriseLicenseInterceptor implements HandlerInterceptor {

    private static final String PAYMENT_REQUIRED_BODY = "{\"error\":\"Enterprise licence required\"}";

    private final GetEditionUseCase getEdition;

    public EnterpriseLicenseInterceptor(GetEditionUseCase getEdition) {
        this.getEdition = getEdition;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (!requiresEnterprise(handlerMethod)) {
            return true;
        }
        if (getEdition.currentEdition() == Edition.ENTERPRISE) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(PAYMENT_REQUIRED_BODY);
        return false;
    }

    private static boolean requiresEnterprise(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(RequiresEnterprise.class) != null
            || handlerMethod.getBeanType().isAnnotationPresent(RequiresEnterprise.class);
    }
}
