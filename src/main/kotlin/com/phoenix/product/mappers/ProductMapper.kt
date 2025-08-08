package com.phoenix.product.mappers

import com.phoenix.product.api.model.generated.ProductResponse
import com.phoenix.product.repository.model.Product
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class ProductMapper {

    fun toResponse(product: Product): ProductResponse {
        return ProductResponse(
            id = product.id,
            name = product.name,
            description = product.description,
            category = product.category,
            price = product.price,
            brand = product.brand,
            sku = product.sku,
            specifications = product.specifications,
            tags = product.tags,
            createdBy = product.createdBy,
            createdAt = OffsetDateTime.ofInstant(product.createdAt, ZoneOffset.UTC)
        )
    }
}