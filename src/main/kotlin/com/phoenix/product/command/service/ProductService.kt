package com.phoenix.product.command.service

import com.phoenix.product.command.api.model.CreateProductRequest
import com.phoenix.product.command.api.model.UpdateProductRequest
import com.phoenix.product.command.exception.ProductAlreadyExistsException
import com.phoenix.product.command.exception.ProductNotFoundException
import com.phoenix.product.command.repository.ProductRepository
import com.phoenix.product.command.repository.model.Product
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
@Transactional
class ProductService(
    private val productRepository: ProductRepository,
    private val outboxService: OutboxService
) {
    fun createProduct(request: CreateProductRequest): Product {
        // Check if product with same SKU already exists
        if (productRepository.existsBySku(request.sku)) {
            throw ProductAlreadyExistsException("Product with SKU ${request.sku} already exists")
        }

        // Create and save product
        val product = Product(
            id = UUID.randomUUID().toString(),
            name = request.name,
            description = request.description,
            category = request.category,
            price = request.price,
            brand = request.brand,
            sku = request.sku,
            specifications = request.specifications,
            tags = request.tags,
            createdBy = "system", // You might want to get this from security context
            createdAt = Instant.now()
        )

        val savedProduct = productRepository.save(product)

        // Publish event via outbox (same transaction)
        outboxService.publishProductCreatedEvent(savedProduct)

        return savedProduct
    }

    fun getProduct(id: String): Product {
        return productRepository.findById(id).orElse(null)
            ?: throw ProductNotFoundException("Product with id $id not found")
    }

    fun updateProduct(id: String, request: UpdateProductRequest): Product {
        val existingProduct = getProduct(id)

        // Check if SKU is being changed and if new SKU already exists
        if (existingProduct.sku != request.sku && productRepository.existsBySku(request.sku)) {
            throw ProductAlreadyExistsException("Product with SKU ${request.sku} already exists")
        }

        val updatedProduct = existingProduct.copy(
            name = request.name,
            description = request.description,
            category = request.category,
            price = request.price,
            brand = request.brand,
            sku = request.sku,
            specifications = request.specifications,
            tags = request.tags,
            updatedAt = Instant.now(),
            version = existingProduct.version + 1
        )

        val savedProduct = productRepository.save(updatedProduct)

        // Publish update event
        outboxService.publishProductUpdatedEvent(savedProduct)

        return savedProduct
    }

    fun deleteProduct(id: String) {
        val product = getProduct(id)
        productRepository.delete(product)
        // You might want to publish a ProductDeleted event here too
    }
}