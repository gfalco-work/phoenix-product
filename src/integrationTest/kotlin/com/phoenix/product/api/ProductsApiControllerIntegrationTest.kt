package com.phoenix.product.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.config.SharedPostgresContainer
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.ProductRepository
import io.mockk.every
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductsApiControllerIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun overrideProps(registry: DynamicPropertyRegistry) {
            val container = SharedPostgresContainer
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${container.host}:${container.getMappedPort(5432)}/${container.databaseName}"
            }
            registry.add("spring.r2dbc.username") { container.username }
            registry.add("spring.r2dbc.password") { container.password }

            registry.add("spring.flyway.url") { container.jdbcUrl }
            registry.add("spring.flyway.user") { container.username }
            registry.add("spring.flyway.password") { container.password }
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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setup() {
        GlobalOpenTelemetry.resetForTest();
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
            category = "Electronics",
            description = "Test Description",
            price = 99.99,
            brand = "Test Brand",
            sku = "TEST-001",
            createdBy = "peppe",
            specifications = """{"color":"blue","size":"medium"}""",
            tags = """["test","electronics"]"""
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
            category = "Electronics",
            description = "Test Description",
            price = 99.99,
            brand = "Test Brand",
            sku = "DUPLICATE-SKU",
            createdBy = "peppe",
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
            .jsonPath("$.error").isEqualTo("Product with SKU DUPLICATE-SKU already exists")
    }

    @Test
    fun `should validate required fields`() {
        val invalidRequest = CreateProductRequest(
            name = "",
            category = "",
            description = "",
            price = -1.0,
            brand = "",
            sku = "",
            createdBy = "",
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

    @Test
    fun `should update product successfully`() {
        // First create a product
        val createRequest = CreateProductRequest(
            name = "Original Product",
            category = "Electronics",
            description = "Original Description",
            price = 99.99,
            brand = "Original Brand",
            sku = "ORIGINAL-001",
            createdBy = "peppe",
            specifications = """{"color":"blue","size":"medium"}""",
            tags = """["original","electronics"]"""
        )

        val createResponse = webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        val productId = objectMapper.readTree(createResponse.responseBody)["id"].asLong()

        // Now update the product
        val updateRequest = UpdateProductRequest(
            name = "Updated Product",
            category = "Electronics",
            description = "Updated Description",
            price = 149.99,
            brand = "Updated Brand",
            sku = "UPDATED-001",
            specifications = """{"color":"red","size":"large"}""",
            tags = """["updated","electronics"]"""
        )

        webTestClient.put()
            .uri("/api/v1/products/$productId")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateRequest)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(productId)
            .jsonPath("$.name").isEqualTo("Updated Product")
            .jsonPath("$.price").isEqualTo(149.99)
            .jsonPath("$.brand").isEqualTo("Updated Brand")
            .jsonPath("$.sku").isEqualTo("UPDATED-001")

        // Verify product was updated in database
        runBlocking {
            val updatedProduct = productRepository.findById(productId).block()!!
            assert(updatedProduct.name == "Updated Product")
            assert(updatedProduct.price == 149.99)

            // Verify outbox event was created for update
            val outboxEvents = outboxRepository.findAll().collectList().block()!!
            assert(outboxEvents.size == 2) // Create + Update events
            assert(outboxEvents.any { it.eventType == "ProductUpdated" })
        }
    }

    @Test
    fun `should return 404 when updating non-existent product`() {
        val updateRequest = UpdateProductRequest(
            name = "Updated Product",
            price = 149.99
        )

        webTestClient.put()
            .uri("/api/v1/products/999")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateRequest)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error").exists()
    }

    @Test
    fun `should return conflict when updating with duplicate SKU`() {
        // Create first product
        val firstProduct = CreateProductRequest(
            name = "First Product",
            category = "Electronics",
            price = 99.99,
            brand = "Brand A",
            sku = "FIRST-001",
            createdBy = "peppe"
        )

        webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(firstProduct)
            .exchange()
            .expectStatus().isCreated

        // Create second product
        val secondProduct = CreateProductRequest(
            name = "Second Product",
            category = "Electronics",
            price = 149.99,
            brand = "Brand B",
            sku = "SECOND-001",
            createdBy = "peppe"
        )

        val createResponse = webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(secondProduct)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        val secondProductId = objectMapper.readTree(createResponse.responseBody)["id"].asLong()

        // Try to update second product with first product's SKU
        val updateRequest = UpdateProductRequest(
            name = "Updated Second Product",
            sku = "FIRST-001" // This should cause conflict
        )

        webTestClient.put()
            .uri("/api/v1/products/$secondProductId")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updateRequest)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("Product with SKU FIRST-001 already exists")
    }

    @Test
    fun `should delete product successfully`() {
        // First create a product
        val createRequest = CreateProductRequest(
            name = "Product to Delete",
            category = "Electronics",
            price = 99.99,
            brand = "Test Brand",
            sku = "DELETE-001",
            createdBy = "peppe"
        )

        val createResponse = webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        val productId = objectMapper.readTree(createResponse.responseBody)["id"].asLong()

        // Delete the product
        webTestClient.delete()
            .uri("/api/v1/products/$productId")
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isNoContent

        // Verify product was deleted from database
        runBlocking {
            val deletedProduct = productRepository.findById(productId).block()
            assert(deletedProduct == null)

            // Verify outbox event was created for delete
            val outboxEvents = outboxRepository.findAll().collectList().block()!!
            assert(outboxEvents.size == 2) // Create + Delete events
            assert(outboxEvents.any { it.eventType == "ProductDeleted" })
        }
    }

    @Test
    fun `should return 404 when deleting non-existent product`() {
        webTestClient.delete()
            .uri("/api/v1/products/999")
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error").exists()
    }

    @Test
    fun `should get product successfully`() {
        // First create a product
        val createRequest = CreateProductRequest(
            name = "Product to Get",
            category = "Electronics",
            price = 99.99,
            brand = "Test Brand",
            sku = "Product-001",
            createdBy = "peppe"
        )

        val createResponse = webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        val productId = objectMapper.readTree(createResponse.responseBody)["id"].asLong()

        // Get the product
        webTestClient.get()
            .uri("/api/v1/products/$productId")
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isOk

        // Verify product was deleted from database
        runBlocking {
            val retrievedProduct = productRepository.findById(productId).block()
            assertNotNull(retrievedProduct)

            // Verify outbox event was created for delete
            val outboxEvents = outboxRepository.findAll().collectList().block()!!
            assert(outboxEvents.size == 1) // Create events
        }
    }

    @Test
    fun `should return 404 when getting non-existent product`() {
        webTestClient.get()
            .uri("/api/v1/products/999")
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error").exists()
    }

    @Test
    fun `should return all products`() {
        val createRequest1 = CreateProductRequest(
            name = "Product to Get",
            category = "Electronics",
            price = 99.99,
            brand = "Test Brand",
            sku = "Product-001",
            createdBy = "peppe"
        )
        val createRequest2 = createRequest1.copy(brand = "M&S", category = "foods", sku = "Product-002")

        webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest1)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        webTestClient.post()
            .uri("/api/v1/products")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createRequest2)
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        // Get the product
        webTestClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/api/v1/products")
                    .queryParam("category", "foods")
                    .build()
            }
            .header("Authorization", "Bearer test-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            .jsonPath("$.content[0].category").isEqualTo("foods")
            .jsonPath("$.content[0].brand").isEqualTo("M&S")

        // Verify DB state directly
        runBlocking {
            val retrievedProducts = productRepository
                .findByFilters("foods", null, "id", "DESC", 10, 0)
                .collectList()
                .block()!!

            assert(retrievedProducts.size == 1)

            val outboxEvents = outboxRepository.findAll().collectList().block()!!
            assert(outboxEvents.size == 2) // 2 create events
        }
    }
}