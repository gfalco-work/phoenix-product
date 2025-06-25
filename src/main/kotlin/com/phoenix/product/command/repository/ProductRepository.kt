package com.phoenix.product.command.repository

import com.phoenix.product.command.repository.model.Product
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ProductRepository : MongoRepository<Product, String> {

    fun findByCreatedBy(createdBy: String): List<Product>

    fun findByCreatedAtAfter(date: Instant): List<Product>

    @Query("{ 'name': { \$regex: ?0, \$options: 'i' } }")
    fun findByNameIgnoreCase(name: String): List<Product>

    fun findBySku(sku: String): Product?

    fun findByCategory(category: String): List<Product>

    fun findByBrand(brand: String): List<Product>

    @Query("{ 'price': { \$gte: ?0, \$lte: ?1 } }")
    fun findByPriceRange(minPrice: Double, maxPrice: Double): List<Product>
}