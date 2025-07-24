package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.api.model.UpdateProductRequest
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.Instant

@Service
@Transactional
class ProductService(
    private val productRepository: ProductRepository,
    private val outboxService: OutboxService,
    private val objectMapper: ObjectMapper
) {

    fun createProduct(request: CreateProductRequest): Mono<Product> {
        val product = Product(
            name = request.name,
            description = request.description,
            category = request.category,
            price = request.price.toDouble(),
            brand = request.brand,
            sku = request.sku,
            specifications = request.specifications?.let { objectMapper.writeValueAsString(it) },
            tags = request.tags?.let { objectMapper.writeValueAsString(it) },
            createdBy = "system", // You might want to get this from security context
            createdAt = Instant.now()
        )

        return productRepository.save(product)
            .flatMap { savedProduct ->
                outboxService.publishProductCreatedEvent(savedProduct)
                    .thenReturn(savedProduct)
            }
            .onErrorMap(DataIntegrityViolationException::class.java) {
                ProductConcurrentModificationException("Product with SKU ${request.sku} already exists")
            }
    }

    fun getProduct(id: Long): Mono<Product> {
        return productRepository.findById(id)
            .switchIfEmpty(
                Mono.error(ProductNotFoundException("Product with id $id not found"))
            )
    }

    fun updateProduct(id: Long, request: UpdateProductRequest): Mono<Product> {
        return getProduct(id)
            .flatMap { existingProduct ->
                val updatedProduct = existingProduct.copy(
                    name = request.name,
                    description = request.description,
                    category = request.category,
                    price = request.price.toDouble(),
                    brand = request.brand,
                    sku = request.sku,
                    specifications = request.specifications?.let { objectMapper.writeValueAsString(it) },
                    tags = request.tags?.let { objectMapper.writeValueAsString(it) },
                    updatedAt = Instant.now()
                )

                productRepository.save(updatedProduct)
                    .flatMap { saved ->
                        outboxService.publishProductUpdatedEvent(saved)
                            .thenReturn(saved)
                    }
            }
            .onErrorMap(OptimisticLockingFailureException::class.java) {
                ProductConcurrentModificationException(
                    "Product was modified by another process. Please refresh and try again."
                )
            }
            .onErrorMap(DataIntegrityViolationException::class.java) {
                ProductConcurrentModificationException("Product with SKU ${request.sku} already exists")
            }
    }

    fun deleteProduct(id: Long): Mono<Void> {
        return getProduct(id)
            .flatMap { product ->
                productRepository.delete(product)
                    .then(outboxService.publishProductDeletedEvent(product.id!!, "user"))
            }
    }
}