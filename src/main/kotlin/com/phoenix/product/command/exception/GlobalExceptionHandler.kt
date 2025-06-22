package com.phoenix.product.command.exception

import com.phoenix.product.command.api.model.ErrorResponse
import org.springframework.http.HttpStatus
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

    @ExceptionHandler(ProductAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleProductAlreadyExists(ex: ProductAlreadyExistsException): ErrorResponse {
        return ErrorResponse(
            error = "PRODUCT_ALREADY_EXISTS",
            message = ex.message ?: "Product already exists"
        )
    }
}