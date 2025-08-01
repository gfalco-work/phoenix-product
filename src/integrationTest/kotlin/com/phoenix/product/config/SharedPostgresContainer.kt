package com.phoenix.product.config

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName

object SharedPostgresContainer {

    @Container
    @ServiceConnection
    val instance: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:17")).apply {
            withDatabaseName("productdb")
            withUsername("test")
            withPassword("test")
            start()
        }
}