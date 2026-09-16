package org.km.llmwiki.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.io.IOException;

/**
 * Browser static-asset currentness contract (Refs #479).
 *
 * <p>Root cause: Spring Boot's default static handling sent no
 * {@code Cache-Control} and only a {@code Last-Modified} validator whose value
 * comes from the reproducible-build pinned JAR entry timestamp
 * ({@code project.build.outputTimestamp}). Content changes therefore kept the
 * same {@code Last-Modified}, so conditional revalidation answered {@code 304}
 * and existing Browser sessions kept stale JS (e.g. pre-#477
 * {@code workspace-ui.js}) until heuristic cache expiry.
 *
 * <p>Contract after this change, scoped to Browser static assets only:
 * <ul>
 *   <li>{@code Cache-Control: no-cache, must-revalidate} (set on Boot's
 *   resource handler via {@code application.yml}; re-asserted here for the
 *   welcome-page mapping so {@code /} matches {@code /index.html}). Never
 *   {@code no-store}: storing with mandatory revalidation is the correct
 *   semantics, not a ban on caching.</li>
 *   <li>Content-based {@code ETag} (MD5 of response bytes, via
 *   {@link ShallowEtagHeaderFilter}) as the sole freshness validator.
 *   {@code Last-Modified} is disabled via
 *   {@code spring.web.resources.cache.use-last-modified=false} so the pinned
 *   timestamp can never again produce a false {@code 304}.</li>
 *   <li>Old sessions holding a pre-#479 entry (only
 *   {@code If-Modified-Since}, no {@code If-None-Match}) get {@code 200} with
 *   the current bytes on their next refresh, because neither the resource
 *   handler nor this filter answers {@code 304} on the timestamp alone.</li>
 * </ul>
 *
 * <p>Out of scope (deliberately untouched): API responses
 * ({@code /api/*} bypass this filter), CSP, local-first/loopback deployment,
 * Workspace authority and persistence semantics, and the runtime version
 * authority ({@code ApplicationVersion} is not used as a URL token, so
 * reproducible builds are unaffected).
 */
@Configuration(proxyBeanMethods = false)
public class StaticAssetCacheConfiguration {

    static final String CACHE_CONTROL_VALUE = "no-cache, must-revalidate";

    @Bean
    public FilterRegistrationBean<Filter> staticAssetEtagFilter() {
        Filter filter = new StaticAssetEtagFilter();
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        registration.setName("staticAssetEtagFilter");
        return registration;
    }

    static class StaticAssetEtagFilter extends ShallowEtagHeaderFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String method = request.getMethod();
            if (!("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) {
                return true;
            }
            String path = request.getRequestURI();
            if (path == null) {
                return true;
            }
            int queryAt = path.indexOf('?');
            if (queryAt >= 0) {
                path = path.substring(0, queryAt);
            }
            if (path.startsWith("/api/") || path.equals("/api") || path.contains("/api/")) {
                return true;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                return false;
            }
            return !(path.endsWith(".js") || path.endsWith(".css"));
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            super.doFilterInternal(request, response, chain);
            if (!shouldNotFilter(request) && response.getHeader("Cache-Control") == null) {
                response.setHeader("Cache-Control", CACHE_CONTROL_VALUE);
            }
        }
    }
}
