package com.kinship.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Strong ETag for the discovery endpoint (docs/lld.md §3.2, docs/hld.md §6).
 *
 * <p>The filter is registered only for {@code /v1/activities/*} so authenticated,
 * user-specific responses (profile, groups, invitations, messages) never acquire
 * an ETag that a shared proxy could use to cache them. A repeat request carrying
 * {@code If-None-Match} with the same value receives {@code 304} and no body.</p>
 */
@Configuration
public class DiscoveryEtagConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> discoveryEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/v1/activities/*");
        registration.setName("discoveryEtagFilter");
        return registration;
    }
}
