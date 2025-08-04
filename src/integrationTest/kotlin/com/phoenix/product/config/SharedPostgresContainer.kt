package com.phoenix.product.config

import org.testcontainers.containers.PostgreSQLContainer

object SharedPostgresContainer : PostgreSQLContainer<SharedPostgresContainer>("postgres:17") {
    init {
        withDatabaseName("test")
        withUsername("test")
        withPassword("test")
        start()
    }
}