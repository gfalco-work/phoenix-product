package com.phoenix.product.exception

import com.phoenix.product.api.model.ErrorResponse
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = KotlinLogging.logger {}

    @ExceptionHandler(ProductNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleProductNotFound(ex: ProductNotFoundException): ErrorResponse {
        log.info { "Product not found: ${ex.message}" }
        return ErrorResponse(
            error = "PRODUCT_NOT_FOUND",
            message = ex.message ?: "Product not found"
        )
    }

    @ExceptionHandler(ProductConcurrentModificationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleProductAlreadyExists(ex: ProductConcurrentModificationException): ErrorResponse {
        log.warn { "Product concurrency issue: ${ex.message}" }
        return ErrorResponse(
            error = "PRODUCT_ALREADY_EXISTS",
            message = ex.message ?: "Product already exists"
        )
    }

    @ExceptionHandler(NumberFormatException::class)
    fun handleNumberFormatException(ex: NumberFormatException): ResponseEntity<String> {
        log.error(ex) { "Invalid ID format received" }
        return ResponseEntity.badRequest().body("Invalid ID format: must be a number.")
    }
}