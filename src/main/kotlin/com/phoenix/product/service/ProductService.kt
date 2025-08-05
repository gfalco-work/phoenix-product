package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.observability.tracing.services.ObservabilityService
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
    private val objectMapper: ObjectMapper,
    private val observabilityService: ObservabilityService
) {
    fun createProduct(request: CreateProductRequest): Mono<Product> {
        return observabilityService.wrapMono(
            "product-service",
            "createProduct",
            request,
            { req ->
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

                productRepository.save(product)
                    .flatMap { savedProduct ->
                        outboxService.publishProductCreatedEvent(savedProduct)
                            .thenReturn(savedProduct)
                    }
                    .onErrorMap(DataIntegrityViolationException::class.java) {
                        ProductConcurrentModificationException("Product with SKU ${req.sku} already exists")
                    }
            },
            mapOf(
                "operation.type" to "create",
                "product.sku" to request.sku,
                "product.category" to request.category
            )
        )
    }

    fun updateProduct(id: Long, request: UpdateProductRequest): Mono<Product> {
        return observabilityService.wrapMono(
            "product-service",
            "updateProduct",
            request,
            { req ->
                getProduct(id)
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
            },
            mapOf(
                "operation.type" to "update",
                "product.sku" to request.sku,
                "product.category" to request.category
            )
        )
    }

    fun getProduct(id: Long): Mono<Product> {
        return observabilityService.wrapMono(
            "product-service",
            "getProduct",
            id,
            { productId ->
                productRepository.findById(productId)
                    .switchIfEmpty(Mono.error(ProductNotFoundException("Product with id $productId not found")))
            },
            mapOf(
                "operation.type" to "read",
                "product.id" to id.toString()
            )
        )
    }

    fun deleteProduct(id: Long): Mono<Void> {
        return observabilityService.wrapMono(
            "product-service",
            "deleteProduct",
            id,
            { productId ->
                getProduct(productId)
                    .flatMap { product ->
                        productRepository.delete(product)
                            .then(outboxService.publishProductDeletedEvent(product.id!!, "user"))
                    }
            },
            mapOf(
                "operation.type" to "delete",
                "product.id" to id.toString()
            )
        )
    }
}