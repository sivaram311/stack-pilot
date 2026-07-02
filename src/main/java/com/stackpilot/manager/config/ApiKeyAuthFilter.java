package com.stackpilot.manager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-StackPilot-Api-Key";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/status"
    );

    private static boolean isPublicStaticPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return "/".equals(path)
                || "/index.html".equals(path)
                || path.startsWith("/css/")
                || path.startsWith("/js/");
    }

    private final StackPilotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiKeyAuthFilter(StackPilotProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        StackPilotProperties.AuthSettings auth = properties.getAuth();
        if (!auth.isEnabled()) {
            return true;
        }
        String configuredKey = auth.getApiKey();
        if (configuredKey == null || configuredKey.isBlank()) {
            return true;
        }
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path) || isPublicStaticPath(path)) {
            return true;
        }
        if (auth.isTrustProxyHeaders() && hasProxyHeaders(request)) {
            return true;
        }
        if (auth.isAllowLocalhostWithoutKey() && isLocalRequest(request)) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String configuredKey = properties.getAuth().getApiKey();
        String providedKey = extractApiKey(request);

        if (configuredKey.equals(providedKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", "Unauthorized — valid API key required");
        body.put("authRequired", true);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private boolean hasProxyHeaders(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        String forwarded = request.getHeader("X-Forwarded-For");
        return (realIp != null && !realIp.isBlank()) || (forwarded != null && !forwarded.isBlank());
    }

    private String extractApiKey(HttpServletRequest request) {
        String header = request.getHeader(API_KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return "";
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return isLoopback(first);
        }
        return false;
    }

    private boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if ("127.0.0.1".equals(host) || "0:0:0:0:0:0:0:1".equals(host) || "::1".equals(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
