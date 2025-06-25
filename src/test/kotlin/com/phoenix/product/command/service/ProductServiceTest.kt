package com.phoenix.product.command.service

import com.mongodb.DuplicateKeyException
import com.mongodb.ServerAddress
import com.mongodb.WriteConcernResult
import com.phoenix.product.command.api.model.CreateProductRequest
import com.phoenix.product.command.api.model.UpdateProductRequest
import com.phoenix.product.command.exception.ProductConcurrentModificationException
import com.phoenix.product.command.exception.ProductNotFoundException
import com.phoenix.product.command.repository.ProductRepository
import com.phoenix.product.command.repository.model.Product
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.verify
import org.bson.BsonDocument
import org.bson.BsonString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@ExtendWith(MockKExtension::class)
class ProductServiceTest {

    @MockK
    private lateinit var productRepository: ProductRepository

    @MockK
    private lateinit var mongoTemplate: MongoTemplate

    @MockK
    private lateinit var outboxService: OutboxService

    @InjectMockKs
    private lateinit var productService: ProductService

    private lateinit var sampleProduct: Product
    private lateinit var createRequest: CreateProductRequest
    private lateinit var updateRequest: UpdateProductRequest

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)

        sampleProduct = Product(
            id = "test-id",
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = mapOf("color" to "blue", "size" to "medium"),
            tags = listOf("electronics", "gadget"),
            version = 1L,
            createdBy = "system",
            createdAt = Instant.now()
        )

        createRequest = CreateProductRequest(
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99.toBigDecimal(),
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = mapOf("color" to "blue"),
            tags = listOf("electronics")
        )

        updateRequest = UpdateProductRequest(
            name = "Updated Product",
            description = "Updated Description",
            category = "Electronics",
            price = 109.99.toBigDecimal(),
            brand = "UpdatedBrand",
            sku = "UPDATED-SKU-001",
            specifications = mapOf("color" to "red"),
            tags = listOf("electronics", "updated")
        )
    }

    @Test
    fun `createProduct should save product and publish event successfully`() {
        // Given
        every { productRepository.save(any<Product>()) } returns sampleProduct
        every { outboxService.publishProductCreatedEvent(any()) } just Runs

        // When
        val result = productService.createProduct(createRequest)

        // Then
        assertNotNull(result)
        assertEquals(sampleProduct.name, result.name)
        assertEquals(sampleProduct.sku, result.sku)

        verify { productRepository.save(any<Product>()) }
        verify { outboxService.publishProductCreatedEvent(sampleProduct) }
    }

    @Test
    fun `createProduct should throw exception when SKU already exists`() {
        // Given
        val serverAddress = ServerAddress("localhost", 27017)
        val response = BsonDocument("error", BsonString("E11000 duplicate key error"))
        val writeConcernResult = WriteConcernResult.acknowledged(1, true, null)

        every { productRepository.save(any<Product>()) } throws DuplicateKeyException(
            response,
            serverAddress,
            writeConcernResult
        )

        // When & Then
        assertThrows<ProductConcurrentModificationException> {
            productService.createProduct(createRequest)
        }
    }

    @Test
    fun `getProduct should return product when found`() {
        // Given
        every { productRepository.findById("test-id") } returns Optional.of(sampleProduct)

        // When
        val result = productService.getProduct("test-id")

        // Then
        assertEquals(sampleProduct, result)
        verify { productRepository.findById("test-id") }
    }

    @Test
    fun `getProduct should throw exception when not found`() {
        // Given
        every { productRepository.findById("non-existent") } returns Optional.empty()

        // When & Then
        assertThrows<ProductNotFoundException> {
            productService.getProduct("non-existent")
        }
    }

    @Test
    fun `updateProduct should update and publish event successfully`() {
        // Given
        val updatedProduct = sampleProduct.copy(name = "Updated Product", version = 2L)

        every { productRepository.findById("test-id") } returns Optional.of(sampleProduct)
        every {
            mongoTemplate.findAndModify(
                any<Query>(),
                any<Update>(),
                any<FindAndModifyOptions>(),
                Product::class.java
            )
        } returns updatedProduct
        every { outboxService.publishProductUpdatedEvent(any()) } just Runs

        // When
        val result = productService.updateProduct("test-id", updateRequest)

        // Then
        assertEquals(updatedProduct, result)
        verify { outboxService.publishProductUpdatedEvent(updatedProduct) }
    }

    @Test
    fun `updateProduct should throw exception when concurrent modification occurs`() {
        // Given
        every { productRepository.findById("test-id") } returns Optional.of(sampleProduct)
        every {
            mongoTemplate.findAndModify(
                any<Query>(),
                any<Update>(),
                any<FindAndModifyOptions>(),
                Product::class.java
            )
        } returns null

        // When & Then
        assertThrows<ProductConcurrentModificationException> {
            productService.updateProduct("test-id", updateRequest)
        }
    }

    @Test
    fun `deleteProduct should remove product and publish event successfully`() {
        // Given
        every { mongoTemplate.findAndRemove(any<Query>(), Product::class.java) } returns sampleProduct
        every { outboxService.publishProductDeletedEvent(any(), any()) } just Runs

        // When
        productService.deleteProduct("test-id")

        // Then
        verify { mongoTemplate.findAndRemove(any<Query>(), Product::class.java) }
        verify { outboxService.publishProductDeletedEvent("test-id", "user") }
    }

    @Test
    fun `deleteProduct should throw exception when product not found`() {
        // Given
        every { mongoTemplate.findAndRemove(any<Query>(), Product::class.java) } returns null

        // When & Then
        assertThrows<ProductNotFoundException> {
            productService.deleteProduct("non-existent")
        }
    }
}