package com.phoenix.product.command.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "products")
data class Product(
    @Id
    val id: String,
    val name: String,
    val description: String?,
    val category: String,
    val price: Double,
    val brand: String,

    @Indexed(unique = true) // This creates unique index for SKU
    val sku: String,

    val specifications: Map<String, String>? = null,
    val tags: List<String>? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant? = null,

    @Version // Spring Data MongoDB will handle optimistic locking
    val version: Long = 1L
)