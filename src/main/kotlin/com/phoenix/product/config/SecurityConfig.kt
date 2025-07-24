package com.phoenix.product.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/public/**")
                    .hasAuthority("SCOPE_api.read.public")
                    .pathMatchers(HttpMethod.GET, "/**")
                    .hasAnyAuthority("SCOPE_api.read", "SCOPE_service.full")
                    .pathMatchers(HttpMethod.POST, "/**")
                    .hasAnyAuthority("SCOPE_api.write", "SCOPE_service.full")
                    .pathMatchers(HttpMethod.PUT, "/**")
                    .hasAnyAuthority("SCOPE_api.write", "SCOPE_service.full")
                    .pathMatchers(HttpMethod.DELETE, "/**")
                    .hasAnyAuthority("SCOPE_api.write", "SCOPE_service.full")
                    .anyExchange()
                    .authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
            }
            .build()
    }
}