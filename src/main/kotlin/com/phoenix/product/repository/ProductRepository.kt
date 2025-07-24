package com.phoenix.product.repository

import com.phoenix.product.repository.model.Product
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

@Repository
interface ProductRepository : R2dbcRepository<Product, Long> {
    fun findByCreatedBy(createdBy: String): Flux<Product>
    fun findByCreatedAtAfter(date: Instant): Flux<Product>
    @Query("SELECT * FROM products WHERE LOWER(name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun findByNameIgnoreCase(name: String): Flux<Product>
    fun findBySku(sku: String): Mono<Product>
    fun findByCategory(category: String): Flux<Product>
    fun findByBrand(brand: String): Flux<Product>
    @Query("SELECT * FROM products WHERE price >= :minPrice AND price <= :maxPrice")
    fun findByPriceRange(minPrice: Double, maxPrice: Double): Flux<Product>
}