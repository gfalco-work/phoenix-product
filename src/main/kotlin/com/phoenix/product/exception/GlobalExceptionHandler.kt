package com.phoenix.product.exception

import com.phoenix.product.api.model.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleProductNotFound(ex: ProductNotFoundException): ErrorResponse {
        return ErrorResponse(
            error = "PRODUCT_NOT_FOUND",
            message = ex.message ?: "Product not found"
        )
    }

    @ExceptionHandler(ProductConcurrentModificationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleProductAlreadyExists(ex: ProductConcurrentModificationException): ErrorResponse {
        return ErrorResponse(
            error = "PRODUCT_ALREADY_EXISTS",
            message = ex.message ?: "Product already exists"
        )
    }

    @ExceptionHandler(NumberFormatException::class)
    fun handleNumberFormatException(ex: NumberFormatException): ResponseEntity<String> {
        return ResponseEntity.badRequest().body("Invalid ID format: must be a number.")
    }
}