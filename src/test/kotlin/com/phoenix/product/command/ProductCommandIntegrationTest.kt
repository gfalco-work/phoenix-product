package com.phoenix.product.command

import com.phoenix.product.command.api.model.CreateProductRequest
import com.phoenix.product.command.repository.OutboxRepository
import com.phoenix.product.command.repository.ProductRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProductCommandIntegrationTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @BeforeEach
    fun setup() {
        productRepository.deleteAll()
        outboxRepository.deleteAll()
    }

    @Test
    fun `should create product successfully`() {
        val request = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = BigDecimal("99.99"),
            brand = "Test Brand",
            sku = "TEST-001",
            specifications = mapOf("color" to "blue", "size" to "medium"),
            tags = listOf("test", "electronics")
        )

        webTestClient.post()
            .uri("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.name").isEqualTo("Test Product")
            .jsonPath("$.sku").isEqualTo("TEST-001")
            .jsonPath("$.price").isEqualTo(99.99)

        // Verify product was saved
        val products = productRepository.findAll()
        assert(products.size == 1)
        assert(products[0].name == "Test Product")

        // Verify outbox event was created
        val outboxEvents = outboxRepository.findAll()
        assert(outboxEvents.size == 1)
        assert(outboxEvents[0].eventType == "ProductCreated")
        assert(!outboxEvents[0].processed)
    }

    @Test
    fun `should return conflict when creating product with duplicate SKU`() {
        val request = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = BigDecimal("99.99"),
            brand = "Test Brand",
            sku = "DUPLICATE-SKU",
            specifications = null,
            tags = null
        )

        // Create first product
        webTestClient.post()
            .uri("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated

        // Try to create second product with same SKU
        webTestClient.post()
            .uri("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("PRODUCT_ALREADY_EXISTS")
    }

    @Test
    fun `should validate required fields`() {
        val invalidRequest = CreateProductRequest(
            name = "",
            description = "",
            category = "",
            price = BigDecimal("-1.0"),
            brand = "",
            sku = "",
            specifications = null,
            tags = null
        )

        webTestClient.post()
            .uri("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(invalidRequest)
            .exchange()
            .expectStatus().isBadRequest
    }

    @TestConfiguration
    @EnableWebFluxSecurity
    class TestSecurityConfig {

        @Bean
        @Primary
        fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
            return http
                .authorizeExchange { exchanges -> exchanges.anyExchange().permitAll() }
                .csrf { it.disable() }
                .build()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mongoContainer: MongoDBContainer = MongoDBContainer("mongo:7.0")
            .withReuse(true)

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                mongoContainer.getReplicaSetUrl("productdb")
            }
        }
    }
}