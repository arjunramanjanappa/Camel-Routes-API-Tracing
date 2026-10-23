package com.uob.tracer.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Cache policy for the single-page UI, so a user never sits on a stale bundle:
 * <ul>
 *   <li><b>Pages</b> ({@code /}, {@code *.html}, any extension-less path): {@code no-store}. The browser must fetch
 *       {@code index.html} on every load, and that file names the current hashed bundle.</li>
 *   <li><b>Hashed assets</b> ({@code /assets/*}, Vite output such as {@code index-BnbRuSGs.js}): cacheable for a
 *       year and {@code immutable} — the file name changes whenever its content does, so a new build is always a
 *       new URL and an old one can be cached forever.</li>
 * </ul>
 * API calls ({@code /internal/*}) are left alone.
 */
@Component
public class UiCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (path.startsWith("/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else if (isPage(path)) {
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        chain.doFilter(request, response);
    }

    /** The SPA shell: the root, any {@code .html}, or a path without a file extension that isn't an API call. */
    static boolean isPage(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return true;
        }
        if (path.startsWith("/internal/")) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return last.endsWith(".html") || !last.contains(".");
    }
}
