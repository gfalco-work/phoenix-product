package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.config.SharedPostgresContainer
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import io.opentelemetry.api.GlobalOpenTelemetry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.test.StepVerifier
import java.time.Instant
import kotlin.test.assertEquals

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductServiceIntegrationTest {

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

    @Autowired
    private lateinit var productService: ProductService

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        GlobalOpenTelemetry.resetForTest();
        productRepository.deleteAll().block()
        outboxRepository.deleteAll().block()
    }

    @Test
    fun `should create product and store outbox event in same transaction`() {
        // Given
        val createRequest = CreateProductRequest(
            name = "Integration Test Product",
            category = "Electronics",
            description = "Test Description",
            price = 99.99,
            brand = "TestBrand",
            sku = "INT-TEST-001",
            createdBy = "peppe",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue")),
            tags = objectMapper.writeValueAsString(listOf("integration", "test"))
        )

        // When & Then
        StepVerifier.create(
            productService.createProduct(createRequest)
                .flatMap { createdProduct ->
                    productRepository.findById(createdProduct.id!!)
                        .zipWith(outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList())
                        .map { tuple ->
                            Triple(createdProduct, tuple.t1, tuple.t2)
                        }
                }
        )
            .assertNext { triple ->
                val (createdProduct, savedProduct, outboxEvents) = triple
                assertEquals(createRequest.name, createdProduct.name)
                assertEquals(createRequest.sku, createdProduct.sku)
                assertEquals(createRequest.price.toDouble(), createdProduct.price)

                // Verify product was saved
                assertEquals(createdProduct, savedProduct)

                // Verify outbox event was created
                assertEquals(1, outboxEvents.size)
                assertEquals(createdProduct.id.toString(), outboxEvents[0].aggregateId)
                assertEquals("ProductCreated", outboxEvents[0].eventType)
            }
            .verifyComplete()
    }

    @Test
    fun `should update product and create outbox event`() {
        // Given
        val createRequest = CreateProductRequest(
            name = "Test Product",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "UPDATE-TEST-001",
            createdBy = "peppe",
            description = "Test Description",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue")),
            tags = objectMapper.writeValueAsString(listOf("test"))
        )

        val updateRequest = UpdateProductRequest(
            name = "Updated Product Name",
            description = "Updated Description",
            category = "Updated Category",
            price = 199.99,
            brand = "UpdatedBrand",
            sku = "UPDATE-TEST-002",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "red", "size" to "large")),
            tags = objectMapper.writeValueAsString(listOf("updated", "integration"))
        )

        // When & Then
        StepVerifier.create(
            productService.createProduct(createRequest)
                .flatMap { initialProduct ->
                    productService.updateProduct(initialProduct.id!!, updateRequest)
                        .flatMap { updatedProduct ->
                            outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList()
                                .map { outboxEvents ->
                                    Triple(initialProduct, updatedProduct, outboxEvents)
                                }
                        }
                }
        )
            .assertNext { triple ->
                val (initialProduct, updatedProduct, outboxEvents) = triple
                assertEquals(updateRequest.name, updatedProduct.name)
                assertEquals(updateRequest.sku, updatedProduct.sku)
                assertEquals(updateRequest.price, updatedProduct.price)
                assertTrue(updatedProduct.version > initialProduct.version)

                // Verify outbox events (create + update)
                assertEquals(2, outboxEvents.size)
                assertTrue(outboxEvents.any { it.eventType == "ProductCreated" })
                assertTrue(outboxEvents.any { it.eventType == "ProductUpdated" })
            }
            .verifyComplete()
    }

    @Test
    fun `should handle concurrent modification during update`() {
        // Given
        val createRequest = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "CONCURRENT-TEST-001",
            createdBy = "peppe",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue")),
            tags = objectMapper.writeValueAsString(listOf("test"))
        )

        val updateRequest1 = UpdateProductRequest(
            name = "First Update",
            description = "First Description",
            category = "Electronics",
            price = 100.00,
            brand = "Brand1",
            sku = "CONCURRENT-TEST-002",
            specifications = "",
            tags = ""
        )

        val updateRequest2 = UpdateProductRequest(
            name = "Second Update",
            description = "Second Description",
            category = "Electronics",
            price = 200.00,
            brand = "Brand2",
            sku = "CONCURRENT-TEST-003",
            specifications = "",
            tags = ""
        )

        StepVerifier.create(
            productService.createProduct(createRequest)
                .flatMap { createdProduct ->
                    // Step 1: Fetch the product and hold it as a stale copy
                    productRepository.findById(createdProduct.id!!)
                        .flatMap { originalProduct ->

                            // Step 2: Perform first update (this will increment the version)
                            productService.updateProduct(createdProduct.id!!, updateRequest1)
                                .thenReturn(originalProduct)
                        }
                        .flatMap { staleProduct ->
                            // Step 3: Try to save using stale version manually to simulate optimistic locking failure
                            val manuallyUpdated = staleProduct.copy(
                                name = updateRequest2.name ?: staleProduct.name,
                                description = updateRequest2.description,
                                category = updateRequest2.category ?: staleProduct.category,
                                price = updateRequest2.price ?: staleProduct.price,
                                brand = updateRequest2.brand ?: staleProduct.brand,
                                sku = updateRequest2.sku ?: staleProduct.sku,
                                specifications = updateRequest2.specifications?.let { objectMapper.writeValueAsString(it) },
                                tags = updateRequest2.tags?.let { objectMapper.writeValueAsString(it) },
                                updatedAt = Instant.now()
                            )

                            productRepository.save(manuallyUpdated)
                        }
                }
        )
            .expectErrorSatisfies { error ->
                assertTrue(error is OptimisticLockingFailureException || error is ProductConcurrentModificationException)
            }
            .verify()
    }


    @Test
    fun `should delete product and create outbox event`() {
        // Given
        val product = createTestProduct("DELETE-TEST-001")

        // When & Then
        StepVerifier.create(productService.deleteProduct(product.id!!))
            .verifyComplete()

        // Verify product was deleted
        StepVerifier.create(productRepository.findById(product.id!!))
            .verifyComplete()

        // Verify outbox events (create + delete)
        val outboxEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList().block()!!
        assertEquals(2, outboxEvents.size)
        assertTrue(outboxEvents.any { it.eventType == "ProductCreated" })
        assertTrue(outboxEvents.any { it.eventType == "ProductDeleted" })
    }

    @Test
    fun `should throw exception when deleting non-existent product`() {
        // When & Then
        StepVerifier.create(productService.deleteProduct(999L))
            .expectError(ProductNotFoundException::class.java)
            .verify()
    }

    @Test
    fun `should prevent duplicate SKU creation`() {
        // Given
        createTestProduct("DUPLICATE-SKU-001")

        val duplicateRequest = CreateProductRequest(
            name = "Duplicate Product",
            description = "Different Description",
            category = "Different Category",
            price = 199.99,
            brand = "DifferentBrand",
            createdBy = "peppe",
            sku = "DUPLICATE-SKU-001", // Same SKU
            specifications = "",
            tags = ""
        )

        // When & Then
        StepVerifier.create(productService.createProduct(duplicateRequest))
            .expectError(ProductConcurrentModificationException::class.java)
            .verify()
    }

    @Test
    fun `should get product by id successfully`() {
        // Given
        val createdProduct = createTestProduct("GET-TEST-001")

        // When & Then
        StepVerifier.create(productService.getProduct(createdProduct.id!!))
            .expectNext(createdProduct)
            .verifyComplete()
    }

    @Test
    fun `should get products by category successfully`() {
        // Given: insert a product in 'Electronics' category
        val createdProduct = createTestProduct("GET-TEST-001")

        val pageable = PageRequest.of(0, 5, Sort.unsorted()) // page 0 to include the product

        // When & Then: fetch products by category
        StepVerifier.create(productService.getProducts("Electronics", null, pageable))
            .expectNextMatches { it.sku == createdProduct.sku && it.category == "Electronics" }
            .verifyComplete()
    }

    @Test
    fun `should not find products by different brand successfully`() {
        // Given: insert a product in 'Electronics' category
        createTestProduct("GET-TEST-001")

        val pageable = PageRequest.of(0, 5, Sort.unsorted()) // page 0 to include the product

        // When & Then: fetch products by category
        StepVerifier.create(productService.getProducts("Electronics", "M&S", pageable))
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun `should throw exception when getting non-existent product`() {
        // When & Then
        StepVerifier.create(productService.getProduct(999L))
            .expectError(ProductNotFoundException::class.java)
            .verify()
    }

    private fun createTestProduct(sku: String): Product {
        val createRequest = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = sku,
            createdBy = "peppe",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue")),
            tags = objectMapper.writeValueAsString(listOf("test"))
        )
        return productService.createProduct(createRequest).block()!!
    }
}