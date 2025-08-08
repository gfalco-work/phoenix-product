package com.phoenix.product.repository

import com.phoenix.product.repository.model.Product
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface ProductRepository : R2dbcRepository<Product, Long> {

    @Query("""
        SELECT * FROM products
        WHERE (:category IS NULL OR category = :category)
          AND (:brand IS NULL OR brand = :brand)
        ORDER BY 
          CASE WHEN :sortField = 'name' AND :sortDir = 'ASC' THEN name END ASC,
          CASE WHEN :sortField = 'name' AND :sortDir = 'DESC' THEN name END DESC,
          CASE WHEN :sortField = 'price' AND :sortDir = 'ASC' THEN price END ASC,
          CASE WHEN :sortField = 'price' AND :sortDir = 'DESC' THEN price END DESC
        LIMIT :limit OFFSET :offset
    """)
    fun findByFilters(
        category: String?,
        brand: String?,
        sortField: String,
        sortDir: String,
        limit: Int,
        offset: Int
    ): Flux<Product>

    @Query("""
        SELECT COUNT(*) FROM products
        WHERE (:category IS NULL OR category = :category)
          AND (:brand IS NULL OR brand = :brand)
    """)
    fun countByFilters(category: String?, brand: String?): Mono<Long>
}