package com.phoenix.product.api.model

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class UpdateProductRequest(
    @field:NotBlank(message = "Product name is required")
    val name: String,
    @field:NotBlank(message = "Description is required")
    val description: String,
    @field:NotBlank(message = "Category is required")
    val category: String,
    @field:DecimalMin(value = "0.0", message = "Price must be positive")
    val price: BigDecimal,
    @field:NotBlank(message = "Brand is required")
    val brand: String,
    @field:NotBlank(message = "SKU is required")
    val sku: String,
    val specifications: Map<String, String>? = null,
    val tags: List<String>? = null
)

data class CreateProductRequest(
    @field:NotBlank(message = "Product name is required")
    val name: String,
    @field:NotBlank(message = "Description is required")
    val description: String,
    @field:NotBlank(message = "Category is required")
    val category: String,
    @field:DecimalMin(value = "0.0", message = "Price must be positive")
    val price: BigDecimal,
    @field:NotBlank(message = "Brand is required")
    val brand: String,
    @field:NotBlank(message = "SKU is required")
    val sku: String,
    val specifications: Map<String, String>? = null,
    val tags: List<String>? = null
)