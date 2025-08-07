package com.phoenix.product.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties("phoenix.observability")
class ObservabilityProperties {
    /**
     * Controls whether OpenTelemetry SDK registers as the global singleton.
     *
     * Set to false in tests to avoid conflicts from multiple global registrations
     * when running tests in the same JVM.
     */
    var registerGlobal: Boolean = true

}