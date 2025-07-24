package com.phoenix.product.service

import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.api.model.UpdateProductRequest
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.test.StepVerifier
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgresContainer = PostgreSQLContainer("postgres:15-alpine").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
            start()
        }
    }

    @Autowired
    private lateinit var productService: ProductService

    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @BeforeEach
    fun setUp() {
        productRepository.deleteAll().block()
        outboxRepository.deleteAll().block()
    }

    @Test
    fun `should create product and store outbox event in same transaction`() {
        // Given
        val createRequest = CreateProductRequest(
            name = "Integration Test Product",
            description = "Test Description",
            category = "Electronics",
            price = BigDecimal("99.99"),
            brand = "TestBrand",
            sku = "INT-TEST-001",
            specifications = mapOf("color" to "blue"),
            tags = listOf("integration", "test")
        )

        // When & Then
        StepVerifier.create(productService.createProduct(createRequest))
            .assertNext { createdProduct ->
                assertEquals(createRequest.name, createdProduct.name)
                assertEquals(createRequest.sku, createdProduct.sku)
                assertEquals(createRequest.price.toDouble(), createdProduct.price)

                // Verify product was saved
                val savedProduct = productRepository.findById(createdProduct.id!!).block()
                assertEquals(createdProduct, savedProduct)

                // Verify outbox event was created
                val outboxEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList().block()!!
                assertEquals(1, outboxEvents.size)
                assertEquals(createdProduct.id.toString(), outboxEvents[0].aggregateId)
                assertEquals("ProductCreated", outboxEvents[0].eventType)
            }
            .verifyComplete()
    }

    @Test
    fun `should update product and create outbox event`() {
        // Given - Create initial product
        val initialProduct = createTestProduct("UPDATE-TEST-001")

        val updateRequest = UpdateProductRequest(
            name = "Updated Product Name",
            description = "Updated Description",
            category = "Updated Category",
            price = BigDecimal("199.99"),
            brand = "UpdatedBrand",
            sku = "UPDATE-TEST-002",
            specifications = mapOf("color" to "red", "size" to "large"),
            tags = listOf("updated", "integration")
        )

        // When & Then
        StepVerifier.create(productService.updateProduct(initialProduct.id!!, updateRequest))
            .assertNext { updatedProduct ->
                assertEquals(updateRequest.name, updatedProduct.name)
                assertEquals(updateRequest.sku, updatedProduct.sku)
                assertEquals(updateRequest.price.toDouble(), updatedProduct.price)
                assertTrue(updatedProduct.version > initialProduct.version)

                // Verify outbox events (create + update)
                val outboxEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList().block()!!
                assertEquals(2, outboxEvents.size)
                assertTrue(outboxEvents.any { it.eventType == "ProductCreated" })
                assertTrue(outboxEvents.any { it.eventType == "ProductUpdated" })
            }
            .verifyComplete()
    }

    @Test
    fun `should handle concurrent modification during update`() {
        // Given
        val initialProduct = createTestProduct("CONCURRENT-TEST-001")

        val updateRequest1 = UpdateProductRequest(
            name = "First Update",
            description = "First Description",
            category = "Electronics",
            price = BigDecimal("100.00"),
            brand = "Brand1",
            sku = "CONCURRENT-TEST-002",
            specifications = emptyMap(),
            tags = emptyList()
        )

        val updateRequest2 = UpdateProductRequest(
            name = "Second Update",
            description = "Second Description",
            category = "Electronics",
            price = BigDecimal("200.00"),
            brand = "Brand2",
            sku = "CONCURRENT-TEST-003",
            specifications = emptyMap(),
            tags = emptyList()
        )

        // When - Simulate concurrent updates
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(2)
        val results = mutableListOf<Result<Product>>()

        executor.submit {
            try {
                val result = productService.updateProduct(initialProduct.id!!, updateRequest1).block()!!
                results.add(Result.success(result))
            } catch (e: Exception) {
                results.add(Result.failure(e))
            } finally {
                latch.countDown()
            }
        }

        executor.submit {
            try {
                Thread.sleep(50) // Small delay to ensure race condition
                val result = productService.updateProduct(initialProduct.id!!, updateRequest2).block()!!
                results.add(Result.success(result))
            } catch (e: Exception) {
                results.add(Result.failure(e))
            } finally {
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Then - One should succeed, one should fail with concurrent modification
        assertEquals(2, results.size)
        val successes = results.count { it.isSuccess }
        val failures = results.count { it.isFailure }

        assertEquals(1, successes)
        assertEquals(1, failures)

        val failure = results.first { it.isFailure }
        assertTrue(failure.exceptionOrNull() is ProductConcurrentModificationException)
    }

    @Test
    fun `should delete product and create outbox event`() {
        // Given
        val product = createTestProduct("DELETE-TEST-001")

        // When & Then
        StepVerifier.create(productService.deleteProduct(product.id!!))
            .verifyComplete()

        // Verify product was deleted
        StepVerifier.create(productRepository.findById(product.id))
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
            price = BigDecimal("199.99"),
            brand = "DifferentBrand",
            sku = "DUPLICATE-SKU-001", // Same SKU
            specifications = emptyMap(),
            tags = emptyList()
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
            price = BigDecimal("99.99"),
            brand = "TestBrand",
            sku = sku,
            specifications = mapOf("color" to "blue"),
            tags = listOf("test")
        )
        return productService.createProduct(createRequest).block()!!
    }
}