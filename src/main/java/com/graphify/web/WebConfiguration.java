package com.graphify.web;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serving the single-page web UI from the same origin as the API (web UI spec §3.4–3.5). */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    /**
     * Same-origin scripts only; Mantine injects style elements at runtime, hence 'unsafe-inline' for styles alone.
     * A security policy, not an operational setting.
     */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'self'";

    /** The build puts a content hash in every file name under /assets/, so a name never changes its content. */
    private static final CacheControl IMMUTABLE_ASSETS = CacheControl.maxAge(Duration.ofDays(365)).cachePublic()
            .immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/assets/")
                .setCacheControl(IMMUTABLE_ASSETS);
    }

    @Bean
    FilterRegistrationBean<SpaForwardFilter> spaForwardFilter() {
        FilterRegistrationBean<SpaForwardFilter> registration = new FilterRegistrationBean<>(new SpaForwardFilter());
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        return registration;
    }
}
