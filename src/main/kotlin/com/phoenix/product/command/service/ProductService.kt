package com.phoenix.product.command.service

import com.mongodb.DuplicateKeyException
import com.phoenix.product.command.api.model.CreateProductRequest
import com.phoenix.product.command.api.model.UpdateProductRequest
import com.phoenix.product.command.exception.ProductConcurrentModificationException
import com.phoenix.product.command.exception.ProductNotFoundException
import com.phoenix.product.command.repository.ProductRepository
import com.phoenix.product.command.repository.model.Product
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
@Transactional
class ProductService(
    private val productRepository: ProductRepository,
    private val mongoTemplate: MongoTemplate,
    private val outboxService: OutboxService
) {
    fun createProduct(request: CreateProductRequest): Product {
        val product = Product(
            id = UUID.randomUUID().toString(),
            name = request.name,
            description = request.description,
            category = request.category,
            price = request.price.toDouble(),
            brand = request.brand,
            sku = request.sku,
            specifications = request.specifications,
            tags = request.tags,
            createdBy = "system", // You might want to get this from security context
            createdAt = Instant.now()
        )

        return try {
            val savedProduct = productRepository.save(product)
            outboxService.publishProductCreatedEvent(savedProduct)
            savedProduct
        } catch (ex: DuplicateKeyException) {
            throw ProductConcurrentModificationException("Product with SKU ${request.sku} already exists")
        }
    }

    fun getProduct(id: String): Product {
        return productRepository.findById(id).orElse(null)
            ?: throw ProductNotFoundException("Product with id $id not found")
    }

    fun updateProduct(id: String, request: UpdateProductRequest): Product {
        val existingProduct = getProduct(id)

        val query = Query(
            Criteria.where("id").`is`(id)
                .and("version").`is`(existingProduct.version)
        )

        val update = Update()
            .set("name", request.name)
            .set("description", request.description)
            .set("category", request.category)
            .set("price", request.price)
            .set("brand", request.brand)
            .set("sku", request.sku)
            .set("specifications", request.specifications)
            .set("tags", request.tags)
            .set("updatedAt", Instant.now())
            .inc("version", 1)

        val options = FindAndModifyOptions.options().returnNew(true)

        return try {
            val updatedProduct = mongoTemplate.findAndModify(
                query, update, options, Product::class.java
            ) ?: throw ProductConcurrentModificationException(
                "Product was modified by another process. Please refresh and try again."
            )

            outboxService.publishProductUpdatedEvent(updatedProduct)
            updatedProduct

        } catch (ex: DuplicateKeyException) {
            throw ProductConcurrentModificationException("Product with SKU ${request.sku} already exists")
        }
    }

    // Atomic operation findAndRemove
    fun deleteProduct(id: String) {
        val query = Query(Criteria.where("id").`is`(id))

        val deletedProduct = mongoTemplate.findAndRemove(query, Product::class.java)
            ?: throw ProductNotFoundException("Product with id $id not found")

        outboxService.publishProductDeletedEvent(deletedProduct.id, "user")
    }
}