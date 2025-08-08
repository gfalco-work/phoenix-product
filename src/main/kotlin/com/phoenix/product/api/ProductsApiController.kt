package com.phoenix.product.api

import com.phoenix.product.api.generated.ProductsApi
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.PageableInfo
import com.phoenix.product.api.model.generated.ProductResponse
import com.phoenix.product.api.model.generated.ProductsPageResponse
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.mappers.ProductMapper
import com.phoenix.product.service.ProductService
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class ProductsApiController(
    private val productService: ProductService,
    private val productMapper: ProductMapper
)  : ProductsApi {
    private val log = KotlinLogging.logger {}

    override suspend fun createProduct(createProductRequest: CreateProductRequest): ResponseEntity<ProductResponse> {
        val product = productService.createProduct(createProductRequest).awaitSingle();
        return ResponseEntity.status(HttpStatus.CREATED).body(productMapper.toResponse(product))
    }

    override suspend fun deleteProduct(id: Long): ResponseEntity<Unit> {
        productService.deleteProduct(id).awaitFirstOrNull()
        return ResponseEntity.noContent().build()
    }

    override suspend fun getAllProducts(
        page: Int,
        size: Int,
        category: String?,
        brand: String?,
        sort: String
    ): ResponseEntity<ProductsPageResponse> {
        val pageable = PageRequest.of(page, size, Sort.by(sort))

        val productsFlux = productService.getProducts(category, brand, pageable)
        val productsList = productsFlux.map { productMapper.toResponse(it) }.collectList().awaitSingle()

        val totalCount = productService.countProducts(category, brand).awaitSingle()
        val totalPages = kotlin.math.ceil(totalCount.toDouble() / size).toInt()

        val response = ProductsPageResponse(
            content = productsList,
            pageable = PageableInfo(
                pageNumber = page,
                pageSize = size
            ),
            totalElements = totalCount,
            totalPages = totalPages,
            propertySize = size,
            number = page,
            first = page == 0,
            last = page == totalPages - 1,
            numberOfElements = productsList.size
        )

        return ResponseEntity.ok(response)
    }

    override suspend fun getProductById(id: Long): ResponseEntity<ProductResponse> {
        val product = productService.getProduct(id).awaitSingle()
        return ResponseEntity.status(HttpStatus.OK).body(productMapper.toResponse(product))
    }

    override suspend fun updateProduct(
        id: Long,
        updateProductRequest: UpdateProductRequest
    ): ResponseEntity<ProductResponse> {
        val product = productService.updateProduct(id, updateProductRequest).awaitSingle()
        return ResponseEntity.status(HttpStatus.OK).body(productMapper.toResponse(product))
    }

}