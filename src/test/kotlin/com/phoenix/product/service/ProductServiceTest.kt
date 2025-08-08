package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.observability.tracing.services.ObservabilityService
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
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

    private val fixedInstant: Instant = Instant.parse("2023-01-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        // capture the operation passed to observabilityService.wrapMono and invoke it with the provided input
        val opSlot = slot<Function<Any, Mono<Any>>>()
        every {
            observabilityService.wrapMono<Any, Any>(any(), any(), any(), capture(opSlot), any())
        } answers {
            val input = arg<Any>(2)
            opSlot.captured.apply(input)
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
            createdAt = fixedInstant,
            version = 1L
        )

        createRequest = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            createdBy = "system",
            specifications = """{"color":"blue","size":"medium"}""",
            tags = """["electronics","gadget"]"""
        )

        updateRequest = UpdateProductRequest(
            name = "Updated Product",
            description = "Updated Description",
            category = "Electronics",
            price = 109.99,
            brand = "UpdatedBrand",
            sku = "UPDATED-SKU-001",
            specifications = """{"color":"red"}""",
            tags = """["electronics","updated"]"""
        )
    }

    @Test
    fun `createProduct should save product and publish event successfully`() {
        // Given
        every { objectMapper.writeValueAsString(createRequest.specifications) } returns """{"color":"blue"}"""
        every { objectMapper.writeValueAsString(createRequest.tags) } returns """["electronics"]"""
        val savedSlot = slot<Product>()

        every { productRepository.save(capture(savedSlot)) } returns Mono.just(sampleProduct)
        every { outboxService.publishProductCreatedEvent(sampleProduct) } returns Mono.empty()

        // When
        val result = productService.createProduct(createRequest)

        // Then
        StepVerifier.create(result)
            .expectNextMatches { it.id == sampleProduct.id && it.name == createRequest.name }
            .verifyComplete()

        verify(exactly = 1) { productRepository.save(any<Product>()) }
        verify(exactly = 1) { outboxService.publishProductCreatedEvent(sampleProduct) }

        verifyOrder {
            productRepository.save(any<Product>())
            outboxService.publishProductCreatedEvent(sampleProduct)
        }

        assertThat(savedSlot.captured.name).isEqualTo(createRequest.name)
        assertThat(savedSlot.captured.sku).isEqualTo(createRequest.sku)
        assertThat(savedSlot.captured.specifications).contains("color")
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

        // ensure publish is not called when save fails
        verify(exactly = 0) { outboxService.publishProductCreatedEvent(any()) }
    }

    @Test
    fun `getProduct should return product when found`() {
        // Given
        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)

        // When
        val result = productService.getProduct(1L)

        // Then
        StepVerifier.create(result)
            .expectNextMatches { it.id == sampleProduct.id && it.name == sampleProduct.name }
            .verifyComplete()

        verify(exactly = 1) { productRepository.findById(1L) }
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
        val updatedInstant = fixedInstant.plusSeconds(60)
        val updatedProduct = sampleProduct.copy(
            name = updateRequest.name ?: sampleProduct.name,
            sku = updateRequest.sku ?: sampleProduct.sku,
            price = updateRequest.price?: sampleProduct.price,
            updatedAt = updatedInstant
        )

        val savedSlot = slot<Product>()

        every { productRepository.findById(1L) } returns Mono.just(sampleProduct)
        every { objectMapper.writeValueAsString(updateRequest.specifications) } returns """{"color":"red"}"""
        every { objectMapper.writeValueAsString(updateRequest.tags) } returns """["electronics","updated"]"""
        every { productRepository.save(capture(savedSlot)) } returns Mono.just(updatedProduct)
        every { outboxService.publishProductUpdatedEvent(updatedProduct) } returns Mono.empty()

        // When
        val result = productService.updateProduct(1L, updateRequest)

        // Then
        StepVerifier.create(result)
            .expectNextMatches { it.id == updatedProduct.id && it.name == updateRequest.name }
            .verifyComplete()

        verify(exactly = 1) { productRepository.findById(1L) }
        verify(exactly = 1) { productRepository.save(any<Product>()) }
        verify(exactly = 1) { outboxService.publishProductUpdatedEvent(updatedProduct) }

        verifyOrder {
            productRepository.findById(1L)
            productRepository.save(any<Product>())
            outboxService.publishProductUpdatedEvent(updatedProduct)
        }

        assertThat(savedSlot.captured.name).isEqualTo(updateRequest.name)
        assertThat(savedSlot.captured.sku).isEqualTo(updateRequest.sku)
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

        verify(exactly = 1) { productRepository.findById(1L) }
        verify(exactly = 1) { productRepository.save(any<Product>()) }
        verify(exactly = 0) { outboxService.publishProductUpdatedEvent(any()) }
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

        verify(exactly = 1) { productRepository.findById(999L) }
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

        verify(exactly = 1) { productRepository.delete(sampleProduct) }
        verify(exactly = 1) { outboxService.publishProductDeletedEvent(1L, "user") }
        verifyOrder {
            productRepository.findById(1L)
            productRepository.delete(sampleProduct)
            outboxService.publishProductDeletedEvent(1L, "user")
        }
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

        verify(exactly = 1) { productRepository.findById(999L) }
        verify(exactly = 0) { productRepository.delete(any()) }
        verify(exactly = 0) { outboxService.publishProductDeletedEvent(any(), any()) }
    }

    @Test
    fun `getProducts should return all products sorted by id`() {
        val pageable = PageRequest.of(0, 10, Sort.by("id").ascending()) // page 0, size 10, no sorting specified
        val mockProducts = Flux.fromIterable(listOf(
            sampleProduct,
            sampleProduct.copy(
                id = 2L,
                name = "Another Test Product with id 2",
                brand = "M&S"
            ),
            sampleProduct.copy(
                id = 3L,
                name = "Another Test Product with id 3",
                category = "foods"
            )
        ))

        every { productRepository.findByFilters(null, null, "id", "ASC", 10, 0) } returns mockProducts

        val result = productService.getProducts(null, null, pageable)

        // Assert
        StepVerifier.create(result)
            .expectNextMatches { it.id == 1L }
            .expectNextMatches { it.id == 2L }
            .expectNextMatches { it.id == 3L }
            .expectNextCount(0) // confirms exactly 3 items
            .verifyComplete()

        verify(exactly = 1) { productRepository.findByFilters(null, null, "id", "ASC", 10, 0) }
    }

    @Test
    fun `getProducts should default to sorting by id descending when no sort specified`() {
        val pageable = PageRequest.of(1, 5, Sort.unsorted()) // page 1, size 5, no sort
        val mockProducts = Flux.fromIterable(listOf(
            sampleProduct.copy(id = 3L),
            sampleProduct.copy(id = 2L),
            sampleProduct
        ))

        every { productRepository.findByFilters(null, null, "id", "DESC", 5, 5) } returns mockProducts

        val result = productService.getProducts(null, null, pageable)

        StepVerifier.create(result)
            .expectNextMatches { it.id == 3L }
            .expectNextMatches { it.id == 2L }
            .expectNextMatches { it.id == 1L }
            .verifyComplete()

        verify(exactly = 1) { productRepository.findByFilters(null, null, "id", "DESC", 5, 5) }
    }

    @Test
    fun `getProducts returns only the products with matching category`() {
        val pageable = PageRequest.of(1, 5, Sort.unsorted()) // page 1, size 5, no sort
        val mockProducts = Flux.fromIterable(listOf(
            sampleProduct.copy(id = 3L, category = "foods")
        ))

        every { productRepository.findByFilters("foods", null, "id", "DESC", 5, 5) } returns mockProducts

        val result = productService.getProducts("foods", null, pageable)

        StepVerifier.create(result)
            .expectNextMatches { it.id == 3L && it.category == "foods" }
            .verifyComplete()

        verify(exactly = 1) { productRepository.findByFilters("foods", null, "id", "DESC", 5, 5) }
    }

    @Test
    fun `getProducts returns only the products with matching brand`() {
        val pageable = PageRequest.of(1, 5, Sort.unsorted()) // page 1, size 5, no sort
        val mockProducts = Flux.fromIterable(listOf(
            sampleProduct.copy(id = 2L, brand = "M&S")
        ))

        every { productRepository.findByFilters("foods", "M&S", "id", "DESC", 5, 5) } returns mockProducts

        val result = productService.getProducts("foods", "M&S", pageable)

        StepVerifier.create(result)
            .expectNextMatches { it.id == 2L && it.brand == "M&S" }
            .verifyComplete()

        verify(exactly = 1) { productRepository.findByFilters("foods", "M&S", "id", "DESC", 5, 5) }
    }

    @Test
    fun `countProducts should return the count from repository`() {
        // given
        val category = "Electronics"
        val brand = "Sony"
        val expectedCount = 42L
        every { productRepository.countByFilters(category, brand) } returns Mono.just(expectedCount)

        // when & then
        StepVerifier.create(productService.countProducts(category, brand))
            .expectNext(expectedCount)
            .verifyComplete()

        verify(exactly = 1) { productRepository.countByFilters(category, brand) }
    }
}
