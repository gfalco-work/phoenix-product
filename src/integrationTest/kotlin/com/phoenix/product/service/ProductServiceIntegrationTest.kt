package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.ProductRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.test.StepVerifier
import java.math.BigDecimal
import kotlin.test.assertEquals


@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class ProductServiceIntegrationTest {

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

//    @Test
//    fun `should update product and create outbox event`() {
//        // Given
//        val createRequest = CreateProductRequest(
//            name = "Test Product",
//            description = "Test Description",
//            category = "Electronics",
//            price = BigDecimal("99.99"),
//            brand = "TestBrand",
//            sku = "UPDATE-TEST-001",
//            specifications = mapOf("color" to "blue"),
//            tags = listOf("test")
//        )
//
//        val updateRequest = UpdateProductRequest(
//            name = "Updated Product Name",
//            description = "Updated Description",
//            category = "Updated Category",
//            price = BigDecimal("199.99"),
//            brand = "UpdatedBrand",
//            sku = "UPDATE-TEST-002",
//            specifications = mapOf("color" to "red", "size" to "large"),
//            tags = listOf("updated", "integration")
//        )
//
//        // When & Then
//        StepVerifier.create(
//            productService.createProduct(createRequest)
//                .flatMap { initialProduct ->
//                    productService.updateProduct(initialProduct.id!!, updateRequest)
//                        .flatMap { updatedProduct ->
//                            outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList()
//                                .map { outboxEvents ->
//                                    Triple(initialProduct, updatedProduct, outboxEvents)
//                                }
//                        }
//                }
//        )
//            .assertNext { triple ->
//                val (initialProduct, updatedProduct, outboxEvents) = triple
//                assertEquals(updateRequest.name, updatedProduct.name)
//                assertEquals(updateRequest.sku, updatedProduct.sku)
//                assertEquals(updateRequest.price.toDouble(), updatedProduct.price)
//                assertTrue(updatedProduct.version > initialProduct.version)
//
//                // Verify outbox events (create + update)
//                assertEquals(2, outboxEvents.size)
//                assertTrue(outboxEvents.any { it.eventType == "ProductCreated" })
//                assertTrue(outboxEvents.any { it.eventType == "ProductUpdated" })
//            }
//            .verifyComplete()
//    }
//
//    @Test
//    fun `should handle concurrent modification during update`() {
//        // Given
//        val createRequest = CreateProductRequest(
//            name = "Test Product",
//            description = "Test Description",
//            category = "Electronics",
//            price = BigDecimal("99.99"),
//            brand = "TestBrand",
//            sku = "CONCURRENT-TEST-001",
//            specifications = mapOf("color" to "blue"),
//            tags = listOf("test")
//        )
//
//        val updateRequest1 = UpdateProductRequest(
//            name = "First Update",
//            description = "First Description",
//            category = "Electronics",
//            price = BigDecimal("100.00"),
//            brand = "Brand1",
//            sku = "CONCURRENT-TEST-002",
//            specifications = emptyMap(),
//            tags = emptyList()
//        )
//
//        val updateRequest2 = UpdateProductRequest(
//            name = "Second Update",
//            description = "Second Description",
//            category = "Electronics",
//            price = BigDecimal("200.00"),
//            brand = "Brand2",
//            sku = "CONCURRENT-TEST-003",
//            specifications = emptyMap(),
//            tags = emptyList()
//        )
//
//        StepVerifier.create(
//            productService.createProduct(createRequest)
//                .flatMap { createdProduct ->
//                    // Step 1: Fetch the product and hold it as a stale copy
//                    productRepository.findById(createdProduct.id!!)
//                        .flatMap { originalProduct ->
//
//                            // Step 2: Perform first update (this will increment the version)
//                            productService.updateProduct(createdProduct.id!!, updateRequest1)
//                                .thenReturn(originalProduct)
//                        }
//                        .flatMap { staleProduct ->
//                            // Step 3: Try to save using stale version manually to simulate optimistic locking failure
//                            val manuallyUpdated = staleProduct.copy(
//                                name = updateRequest2.name,
//                                description = updateRequest2.description,
//                                category = updateRequest2.category,
//                                price = updateRequest2.price.toDouble(),
//                                brand = updateRequest2.brand,
//                                sku = updateRequest2.sku,
//                                specifications = updateRequest2.specifications?.let { objectMapper.writeValueAsString(it) },
//                                tags = updateRequest2.tags?.let { objectMapper.writeValueAsString(it) },
//                                updatedAt = Instant.now()
//                            )
//
//                            productRepository.save(manuallyUpdated)
//                        }
//                }
//        )
//            .expectErrorSatisfies { error ->
//                assertTrue(error is OptimisticLockingFailureException || error is ProductConcurrentModificationException)
//            }
//            .verify()
//    }
//
//
//    @Test
//    fun `should delete product and create outbox event`() {
//        // Given
//        val product = createTestProduct("DELETE-TEST-001")
//
//        // When & Then
//        StepVerifier.create(productService.deleteProduct(product.id!!))
//            .verifyComplete()
//
//        // Verify product was deleted
//        StepVerifier.create(productRepository.findById(product.id))
//            .verifyComplete()
//
//        // Verify outbox events (create + delete)
//        val outboxEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc().collectList().block()!!
//        assertEquals(2, outboxEvents.size)
//        assertTrue(outboxEvents.any { it.eventType == "ProductCreated" })
//        assertTrue(outboxEvents.any { it.eventType == "ProductDeleted" })
//    }
//
//    @Test
//    fun `should throw exception when deleting non-existent product`() {
//        // When & Then
//        StepVerifier.create(productService.deleteProduct(999L))
//            .expectError(ProductNotFoundException::class.java)
//            .verify()
//    }
//
//    @Test
//    fun `should prevent duplicate SKU creation`() {
//        // Given
//        createTestProduct("DUPLICATE-SKU-001")
//
//        val duplicateRequest = CreateProductRequest(
//            name = "Duplicate Product",
//            description = "Different Description",
//            category = "Different Category",
//            price = BigDecimal("199.99"),
//            brand = "DifferentBrand",
//            sku = "DUPLICATE-SKU-001", // Same SKU
//            specifications = emptyMap(),
//            tags = emptyList()
//        )
//
//        // When & Then
//        StepVerifier.create(productService.createProduct(duplicateRequest))
//            .expectError(ProductConcurrentModificationException::class.java)
//            .verify()
//    }
//
//    @Test
//    fun `should get product by id successfully`() {
//        // Given
//        val createdProduct = createTestProduct("GET-TEST-001")
//
//        // When & Then
//        StepVerifier.create(productService.getProduct(createdProduct.id!!))
//            .expectNext(createdProduct)
//            .verifyComplete()
//    }
//
//    @Test
//    fun `should throw exception when getting non-existent product`() {
//        // When & Then
//        StepVerifier.create(productService.getProduct(999L))
//            .expectError(ProductNotFoundException::class.java)
//            .verify()
//    }
//
//    private fun createTestProduct(sku: String): Product {
//        val createRequest = CreateProductRequest(
//            name = "Test Product",
//            description = "Test Description",
//            category = "Electronics",
//            price = BigDecimal("99.99"),
//            brand = "TestBrand",
//            sku = sku,
//            specifications = mapOf("color" to "blue"),
//            tags = listOf("test")
//        )
//        return productService.createProduct(createRequest).block()!!
//    }
}