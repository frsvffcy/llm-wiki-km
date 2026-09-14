package org.km.llmwiki.web.security;

import jakarta.servlet.Filter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Spring wiring for the application-owned owner security boundary (#417).
 *
 * <p>The boundary is opt-in: {@code app.owner.auth-enabled=false} (the
 * default) leaves the localhost trust model exactly as the #393 baseline
 * evaluated. Enabling it without a valid password hash fails fast at startup
 * instead of silently running unprotected.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OwnerSecurityProperties.class)
public class OwnerSecurityConfiguration {

    @Bean
    public OwnerSessionService ownerSessionService(OwnerSecurityProperties properties) {
        properties.validate();
        return new OwnerSessionService(properties, Clock.systemUTC());
    }

    @Bean
    public OwnerRateLimiter ownerRateLimiter() {
        return new OwnerRateLimiter(Clock.systemUTC());
    }

    @Bean
    public FilterRegistrationBean<Filter> ownerSecurityFilter(
            OwnerSecurityProperties properties,
            OwnerSessionService sessions,
            OwnerRateLimiter rateLimiter) {
        OwnerSecurityFilter filter = new OwnerSecurityFilter(properties, sessions, rateLimiter);
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("ownerSecurityFilter");
        return registration;
    }
}
