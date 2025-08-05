package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.observability.tracing.services.ObservabilityService
import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.api.model.UpdateProductRequest
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.math.BigDecimal
import java.time.Instant
import java.util.function.Function

@ExtendWith(MockKExtension::class)
class ProductServiceTest {

    @MockK
    private lateinit var productRepository: ProductRepository

    @MockK
    private lateinit var outboxService: OutboxService

    @MockK
    private lateinit var objectMapper: ObjectMapper

    @MockK(relaxed = true)
    private lateinit var observabilityService: ObservabilityService

    @InjectMockKs
    private lateinit var productService: ProductService

    private lateinit var sampleProduct: Product
    private lateinit var createRequest: CreateProductRequest
    private lateinit var updateRequest: UpdateProductRequest

    @BeforeEach
    fun setUp() {

        every { observabilityService.wrapMono<Any, Any>(any(), any(), any(), any(), any()) } answers {
            val operation = arg<Function<Any, Mono<Any>>>(3)
            val input = arg<Any>(2)
            operation.apply(input)
        }

        sampleProduct = Product(
            id = 1L,
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = """{"color":"blue","size":"medium"}""",
            tags = """["electronics","gadget"]""",
            createdBy = "system",
            createdAt = Instant.now(),
            version = 1L
        )

        createRequest = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = BigDecimal("99.99"),
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = mapOf("color" to "blue"),
            tags = listOf("electronics")
        )

        updateRequest = UpdateProductRequest(
            name = "Updated Product",
            description = "Updated Description",
            category = "Electronics",
            price = BigDecimal("109.99"),
            brand = "UpdatedBrand",
            sku = "UPDATED-SKU-001",
            specifications = mapOf("color" to "red"),
            tags = listOf("electronics", "updated")
        )
    }

    @Test
    fun `createProduct should save product and publish event successfully`() {
        // Given
        every { objectMapper.writeValueAsString(createRequest.specifications) } returns """{"color":"blue"}"""
        every { objectMapper.writeValueAsString(createRequest.tags) } returns """["electronics"]"""
        every { productRepository.save(any<Product>()) } returns Mono.just(sampleProduct)
        every { outboxService.publishProductCreatedEvent(sampleProduct) } returns Mono.empty()

        // When
        val result = productService.createProduct(createRequest)

        // Then
        StepVerifier.create(result)
            .expectNext(sampleProduct)
            .verifyComplete()

        verify { productRepository.save(any<Product>()) }
        verify { outboxService.publishProductCreatedEvent(sampleProduct) }
    }

    @Test
    fun `createProduct should throw exception when SKU already exists`() {
        // Given
        every { objectMapper.writeValueAsString(createRequest.specifications) } returns """{"color":"blue"}"""
        every { objectMapper.writeValueAsString(createRequest.tags) } returns """["electronics"]"""
        every { productRepository.save(any<Product>()) } returns Mono.error(
            DataIntegrityViolationException("Duplicate key error")
        )

        // When
        val result = productService.createProduct(createRequest)

        // Then
        StepVerifier.create(result)
            .expectError(ProductConcurrentModificationException::class.java)
            .verify()
    }

    @Test
    fun `getProduct should return product when found`() {
        // Given
        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)

        // When
        val result = productService.getProduct(1L)

        // Then
        StepVerifier.create(result)
            .expectNext(sampleProduct)
            .verifyComplete()

        verify { productRepository.findById(1L) }
    }

    @Test
    fun `getProduct should throw exception when not found`() {
        // Given
        every { productRepository.findById(999L) } returns Mono.empty()

        // When
        val result = productService.getProduct(999L)

        // Then
        StepVerifier.create(result)
            .expectError(ProductNotFoundException::class.java)
            .verify()
    }

    @Test
    fun `updateProduct should update and publish event successfully`() {
        // Given
        val updatedProduct = sampleProduct.copy(
            name = updateRequest.name,
            sku = updateRequest.sku,
            price = updateRequest.price.toDouble(),
            updatedAt = Instant.now()
        )

        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)
        every { objectMapper.writeValueAsString(updateRequest.specifications) } returns """{"color":"red"}"""
        every { objectMapper.writeValueAsString(updateRequest.tags) } returns """["electronics","updated"]"""
        every { productRepository.save(any<Product>()) } returns Mono.just(updatedProduct)
        every { outboxService.publishProductUpdatedEvent(updatedProduct) } returns Mono.empty()

        // When
        val result = productService.updateProduct(1L, updateRequest)

        // Then
        StepVerifier.create(result)
            .expectNext(updatedProduct)
            .verifyComplete()

        verify { outboxService.publishProductUpdatedEvent(updatedProduct) }
    }

    @Test
    fun `updateProduct should throw exception when concurrent modification occurs`() {
        // Given
        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)
        every { objectMapper.writeValueAsString(updateRequest.specifications) } returns """{"color":"red"}"""
        every { objectMapper.writeValueAsString(updateRequest.tags) } returns """["electronics","updated"]"""
        every { productRepository.save(any<Product>()) } returns Mono.error(
            OptimisticLockingFailureException("Version conflict")
        )

        // When
        val result = productService.updateProduct(1L, updateRequest)

        // Then
        StepVerifier.create(result)
            .expectError(ProductConcurrentModificationException::class.java)
            .verify()
    }

    @Test
    fun `updateProduct should throw exception when product not found`() {
        // Given
        every { productRepository.findById(999L) } returns Mono.empty()

        // When
        val result = productService.updateProduct(999L, updateRequest)

        // Then
        StepVerifier.create(result)
            .expectError(ProductNotFoundException::class.java)
            .verify()
    }

    @Test
    fun `deleteProduct should remove product and publish event successfully`() {
        // Given
        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)
        every { productRepository.delete(sampleProduct) } returns Mono.empty()
        every { outboxService.publishProductDeletedEvent(1L, "user") } returns Mono.empty()

        // When
        val result = productService.deleteProduct(1L)

        // Then
        StepVerifier.create(result)
            .verifyComplete()

        verify { productRepository.delete(sampleProduct) }
        verify { outboxService.publishProductDeletedEvent(1L, "user") }
    }

    @Test
    fun `deleteProduct should throw exception when product not found`() {
        // Given
        every { productRepository.findById(999L) } returns Mono.empty()

        // When
        val result = productService.deleteProduct(999L)

        // Then
        StepVerifier.create(result)
            .expectError(ProductNotFoundException::class.java)
            .verify()
    }
}