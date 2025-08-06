package com.phoenix.product.api

import com.phoenix.product.api.model.CreateProductRequest
import com.phoenix.product.api.model.ProductResponse
import com.phoenix.product.api.model.UpdateProductRequest
import com.phoenix.product.api.model.toResponse
import com.phoenix.product.service.ProductService
import jakarta.validation.Valid
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/products")
@Validated
class ProductController(
    private val productService: ProductService
) {
    private val log = KotlinLogging.logger {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createProduct(
        @Valid @RequestBody request: CreateProductRequest
    ): Mono<ResponseEntity<ProductResponse>> {
        log.info("Creating a new product with name: ${request.name}")
        return productService.createProduct(request)
            .map { product -> ResponseEntity.status(HttpStatus.CREATED).body(product.toResponse()) }
    }

    @PutMapping("/{id}")
    fun updateProduct(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateProductRequest
    ): Mono<ResponseEntity<ProductResponse>> {
        log.info("Updating product with id: $id")
        return productService.updateProduct(id, request)
            .map { product -> ResponseEntity.status(HttpStatus.OK).body(product.toResponse()) }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProduct(@PathVariable id: Long): Mono<ResponseEntity<Void>> {
        log.info("Deleting product with id: $id")
        return productService.deleteProduct(id)
            .thenReturn(ResponseEntity.noContent().build())
    }
}