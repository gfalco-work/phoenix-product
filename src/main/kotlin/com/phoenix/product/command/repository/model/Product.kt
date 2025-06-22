package com.phoenix.product.command.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Document(collection = "products")
data class Product(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Field("name")
    val name: String,

    @Field("description")
    val description: String,

    @Field("category")
    val category: String,

    @Field("price")
    val price: BigDecimal,

    @Field("brand")
    val brand: String,

    @Field("sku")
    val sku: String,

    @Field("specifications")
    val specifications: Map<String, String>? = null,

    @Field("tags")
    val tags: List<String>? = null,

    @Field("created_by")
    val createdBy: String,

    @Field("created_at")
    val createdAt: Instant = Instant.now(),

    @Field("updated_at")
    val updatedAt: Instant? = null,

    @Field("version")
    val version: Long = 1
)