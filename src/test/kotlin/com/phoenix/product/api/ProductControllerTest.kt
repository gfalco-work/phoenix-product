package com.phoenix.product.api

import com.ninjasquad.springmockk.MockkBean
import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.ProductRepository
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.math.BigDecimal

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProductControllerTest {

    companion object {
        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
            .withDatabaseName("productdb")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgresContainer.host}:${postgresContainer.firstMappedPort}/${postgresContainer.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgresContainer.username }
            registry.add("spring.r2dbc.password") { postgresContainer.password }

            // Add Flyway configuration for test container
            registry.add("spring.flyway.url") {
                "jdbc:postgresql://${postgresContainer.host}:${postgresContainer.firstMappedPort}/${postgresContainer.databaseName}"
            }
            registry.add("spring.flyway.user") { postgresContainer.username }
            registry.add("spring.flyway.password") { postgresContainer.password }
        }
    }

    @MockkBean
    private lateinit var reactiveJwtDecoder: ReactiveJwtDecoder

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @BeforeEach
    fun setup() {
        runBlocking {
            productRepository.deleteAll().block()
            outboxRepository.deleteAll().block()
        }

        val jwt = Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .claim("scope", "api.write service.full")
            .claim("sub", "test-user")
            .build()

        every { reactiveJwtDecoder.decode(any()) } returns Mono.just(jwt)
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
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.name").isEqualTo("Test Product")
            .jsonPath("$.sku").isEqualTo("TEST-001")
            .jsonPath("$.price").isEqualTo(99.99)

        // Verify product was saved
        runBlocking {
            val products = productRepository.findAll().collectList().block()!!
            assert(products.size == 1)
            assert(products[0].name == "Test Product")

            // Verify outbox event was created
            val outboxEvents = outboxRepository.findAll().collectList().block()!!
            assert(outboxEvents.size == 1)
            assert(outboxEvents[0].eventType == "ProductCreated")
            assert(!outboxEvents[0].processed)
        }
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
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated

        // Try to create second product with same SKU
        webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
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
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(invalidRequest)
            .exchange()
            .expectStatus().isBadRequest
    }
}