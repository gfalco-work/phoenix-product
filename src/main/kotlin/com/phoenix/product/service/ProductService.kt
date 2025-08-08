package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.phoenix.observability.tracing.services.ObservabilityService
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.exception.ProductConcurrentModificationException
import com.phoenix.product.exception.ProductNotFoundException
import com.phoenix.product.repository.ProductRepository
import com.phoenix.product.repository.model.Product
import mu.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
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

    private val log = KotlinLogging.logger {}

    fun createProduct(request: CreateProductRequest): Mono<Product> {
        log.info { "Attempting to create product with SKU: ${request.sku}" }

        return observabilityService.wrapMono(
            "product-service",
            "createProduct",
            request,
            { request ->
                val product = Product(
                    name = request.name,
                    description = request.description,
                    category = request.category,
                    price = request.price,
                    brand = request.brand,
                    sku = request.sku,
                    specifications = request.specifications?.let { objectMapper.writeValueAsString(it) },
                    tags = request.tags?.let { objectMapper.writeValueAsString(it) },
                    createdBy = "system", // You might want to get this from security context
                    createdAt = Instant.now()
                )

                productRepository.save(product)
                    .doOnSuccess { saved -> log.info { "Product created successfully: id=${saved.id}, sku=${saved.sku}" } }
                    .flatMap { savedProduct ->
                        outboxService.publishProductCreatedEvent(savedProduct)
                            .thenReturn(savedProduct)
                    }
                    .onErrorMap(DataIntegrityViolationException::class.java) {
                        log.warn(it) { "Duplicate SKU detected during creation: ${request.sku}" }
                        ProductConcurrentModificationException("Product with SKU ${request.sku} already exists")
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
        log.info { "Updating product with id=$id and SKU=${request.sku}" }
        return observabilityService.wrapMono(
            "product-service",
            "updateProduct",
            request,
            { request ->
                getProduct(id)
                    .flatMap { existingProduct ->
                        val updatedProduct = existingProduct.copy(
                            name = request.name ?: existingProduct.name,
                            description = request.description,
                            category = request.category ?: existingProduct.category,
                            price = request.price ?: existingProduct.price,
                            brand = request.brand ?: existingProduct.brand,
                            sku = request.sku ?: existingProduct.sku,
                            specifications = request.specifications?.let { objectMapper.writeValueAsString(it) },
                            tags = request.tags?.let { objectMapper.writeValueAsString(it) },
                            updatedAt = Instant.now()
                        )

                        productRepository.save(updatedProduct)
                            .doOnSuccess { saved -> log.info { "Product updated successfully: id=${saved.id}, sku=${saved.sku}" } }
                            .flatMap { saved ->
                                outboxService.publishProductUpdatedEvent(saved)
                                    .thenReturn(saved)
                            }
                    }
                    .onErrorMap(OptimisticLockingFailureException::class.java) {
                        log.warn(it) { "Optimistic lock failure on product id=$id" }
                        ProductConcurrentModificationException(
                            "Product was modified by another process. Please refresh and try again."
                        )
                    }
                    .onErrorMap(DataIntegrityViolationException::class.java) {
                        log.warn(it) { "Duplicate SKU detected during update: ${request.sku}" }
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
        log.debug { "Fetching product with id=$id" }
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
        log.info { "Deleting product with id=$id" }
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

    fun getProducts(category: String?, brand: String?, pageable: PageRequest): Flux<Product> {
        val sortOrder = pageable.sort.firstOrNull()
        val sortField = sortOrder?.property ?: "id"      // default to id if none provided
        val sortDir = if (sortOrder?.isAscending == true) "ASC" else "DESC"

        return productRepository.findByFilters(
            category,
            brand,
            sortField,
            sortDir,
            pageable.pageSize,
            pageable.offset.toInt()
        )
    }

    fun countProducts(category: String?, brand: String?): Mono<Long> {
        return productRepository.countByFilters(category, brand)
    }
}