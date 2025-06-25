package com.phoenix.product.command.api.model

import com.phoenix.product.command.repository.model.Product
import java.math.BigDecimal
import java.time.Instant

data class ProductResponse(
    val id: String,
    val name: String,
    val description: String?,
    val category: String,
    val price: BigDecimal,
    val brand: String,
    val sku: String,
    val specifications: Map<String, String>?,
    val tags: List<String>?,
    val createdBy: String,
    val createdAt: Instant
)

data class ErrorResponse(
    val error: String,
    val message: String,
    val details: List<String>? = null,
    val timestamp: Instant = Instant.now()
)

// Extension function to convert Product to ProductResponse
fun Product.toResponse() = ProductResponse(
    id = this.id,
    name = this.name,
    description = this.description,
    category = this.category,
    price = BigDecimal.valueOf(this.price),
    brand = this.brand,
    sku = this.sku,
    specifications = this.specifications,
    tags = this.tags,
    createdBy = this.createdBy,
    createdAt = this.createdAt
)