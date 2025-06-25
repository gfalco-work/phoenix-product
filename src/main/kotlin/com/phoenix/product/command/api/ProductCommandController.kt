package com.phoenix.product.command.api

import com.phoenix.product.command.api.model.CreateProductRequest
import com.phoenix.product.command.api.model.ProductResponse
import com.phoenix.product.command.api.model.UpdateProductRequest
import com.phoenix.product.command.api.model.toResponse
import com.phoenix.product.command.service.ProductService
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

@RestController
@RequestMapping("/api/v1/products")
@Validated
class ProductCommandController(
    private val productService: ProductService
) {
    private val log = KotlinLogging.logger {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createProduct(
        @Valid @RequestBody request: CreateProductRequest
    ): ResponseEntity<ProductResponse> {
        log.info("Creating a new product with name: ${request.name}")
        val product = productService.createProduct(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(product.toResponse())
    }

    @PutMapping("/{id}")
    fun updateProduct(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateProductRequest
    ): ResponseEntity<ProductResponse> {
        log.info("Updating product with id: $id")
        val product = productService.updateProduct(id, request)
        return ResponseEntity.ok(product.toResponse())
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProduct(@PathVariable id: String): ResponseEntity<Void> {
        log.info("Deleting product with id: $id")
        productService.deleteProduct(id)
        return ResponseEntity.noContent().build()
    }
}