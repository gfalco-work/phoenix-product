package com.phoenix.product.api.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.phoenix.product.repository.model.Product
import java.math.BigDecimal
import java.time.Instant

private val objectMapper = jacksonObjectMapper()

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
    id = this.id.toString(),
    name = this.name,
    description = this.description,
    category = this.category,
    price = BigDecimal.valueOf(this.price),
    brand = this.brand,
    sku = this.sku,
    specifications = this.specifications?.let {
        objectMapper.readValue<Map<String, String>>(it)
    },
    tags = this.tags?.let {
        objectMapper.readValue<List<String>>(it)
    },
    createdBy = this.createdBy,
    createdAt = this.createdAt
)