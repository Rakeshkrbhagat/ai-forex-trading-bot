package com.forexbot.config;

import com.forexbot.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * Rejects requests to protected endpoints unless they carry a valid
 * {@code Authorization: Bearer <token>} header validated by {@link AuthService}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Attribute name under which the authenticated username is exposed. */
    public static final String AUTH_USER_ATTRIBUTE = "authUser";

    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Allow CORS pre-flight requests through untouched.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<String> user = authService.validate(token);
        if (user.isPresent()) {
            request.setAttribute(AUTH_USER_ATTRIBUTE, user.get());
            return true;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", "Missing or invalid bearer token"));
        return false;
    }

    private static String extractToken(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}

