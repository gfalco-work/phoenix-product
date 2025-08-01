package com.phoenix.product.repository

import com.phoenix.product.repository.model.Product
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository

@Repository
interface ProductRepository : R2dbcRepository<Product, Long> {
}