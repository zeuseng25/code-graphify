package com.graphify.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * A reload or shared link to a UI route (e.g. /repositories/12/graph) must load the app: such GET/HEAD requests are forwarded to
 * index.html. API paths and file requests (a dot in the last segment) are never forwarded.
 */
public class SpaForwardFilter extends OncePerRequestFilter {

    /** The UI's entry document, served by {@link SpaController}. */
    static final String INDEX = "/index.html";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isUiRoute(request)) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isUiRoute(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return false;
        }
        // decoded, like Spring MVC routes it: /%61pi/... is /api/...
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path.isEmpty()) {
            path = "/";
        }
        if (path.equals("/api") || path.startsWith("/api/") || path.equals(INDEX)) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }
}
