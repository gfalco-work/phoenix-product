package com.phoenix.product.api

import com.phoenix.product.api.generated.ProductsApi
import com.phoenix.product.api.model.generated.CreateProductRequest
import com.phoenix.product.api.model.generated.ProductResponse
import com.phoenix.product.api.model.generated.ProductsPageResponse
import com.phoenix.product.api.model.generated.UpdateProductRequest
import com.phoenix.product.mappers.ProductMapper
import com.phoenix.product.service.ProductService
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
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
        log.info { "Creating a new product with name: ${createProductRequest.name}" }
        val product = productService.createProduct(createProductRequest).awaitSingle();
        return ResponseEntity.status(HttpStatus.CREATED).body(productMapper.toResponse(product))
    }

    override suspend fun deleteProduct(id: Long): ResponseEntity<Unit> {
        log.info("Deleting product with id: $id")
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
        TODO("Not yet implemented")
    }

    override suspend fun getProductById(id: Long): ResponseEntity<ProductResponse> {
        TODO("Not yet implemented")
    }

    override suspend fun updateProduct(
        id: Long,
        updateProductRequest: UpdateProductRequest
    ): ResponseEntity<ProductResponse> {
        log.info("Updating product with id: $id")
        val product = productService.updateProduct(id, updateProductRequest).awaitSingle()
        return ResponseEntity.status(HttpStatus.OK).body(productMapper.toResponse(product))
    }

}