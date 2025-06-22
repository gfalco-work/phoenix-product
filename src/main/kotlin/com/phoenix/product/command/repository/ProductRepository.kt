package com.phoenix.product.command.repository

import com.phoenix.product.command.repository.model.Product
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ProductRepository : MongoRepository<Product, String> {

    // Find by created by user
    fun findByCreatedBy(createdBy: String): List<Product>

    // Find products created after a specific date
    fun findByCreatedAtAfter(date: Instant): List<Product>

    // Custom query to find by name (case insensitive)
    @Query("{ 'name': { \$regex: ?0, \$options: 'i' } }")
    fun findByNameIgnoreCase(name: String): List<Product>

    // Check if product exists by name
    fun existsByName(name: String): Boolean

    // Check if product exists by SKU
    fun existsBySku(sku: String): Boolean

    // Find by SKU
    fun findBySku(sku: String): Product?

    // Find by category
    fun findByCategory(category: String): List<Product>

    // Find by brand
    fun findByBrand(brand: String): List<Product>

    // Find products by price range
    @Query("{ 'price': { \$gte: ?0, \$lte: ?1 } }")
    fun findByPriceRange(minPrice: Double, maxPrice: Double): List<Product>

    // Find products with pagination support (built-in)
    // Use: productRepository.findAll(PageRequest.of(page, size))
}