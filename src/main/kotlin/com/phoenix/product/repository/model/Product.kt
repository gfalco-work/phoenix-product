package com.phoenix.product.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("products")
data class Product(
    @Id
    val id: Long? = null,
    val name: String,
    val description: String?,
    val category: String,
    val price: Double,
    val brand: String,
    val sku: String,
    val specifications: String? = null,
    val tags: String? = null,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant? = null,

    @Version
    val version: Long = 0L
)