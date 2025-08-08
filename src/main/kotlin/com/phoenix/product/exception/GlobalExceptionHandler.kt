package com.phoenix.product.exception

import com.phoenix.product.api.model.generated.ErrorResponse
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = KotlinLogging.logger {}

    @ExceptionHandler(ProductNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleProductNotFound(ex: ProductNotFoundException, request: ServerHttpRequest): ErrorResponse {
        log.info { "Product not found: ${ex.message}" }
        return ErrorResponse(
            error = ex.message,
            timestamp = OffsetDateTime.now(),
            path = request.path.value()
        )
    }

    @ExceptionHandler(ProductConcurrentModificationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleProductAlreadyExists(ex: ProductConcurrentModificationException, request: ServerHttpRequest): ErrorResponse {
        log.warn { "Product concurrency issue: ${ex.message}" }
        return ErrorResponse(
            error = ex.message,
            timestamp = OffsetDateTime.now(),
            path = request.path.value()
        )
    }

    @ExceptionHandler(NumberFormatException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleNumberFormatException(ex: NumberFormatException, request: ServerHttpRequest): ErrorResponse {
        log.error(ex) { "Invalid ID format received" }
        return ErrorResponse(
            error = "Invalid ID format: must be a number.",
            timestamp = OffsetDateTime.now(),
            path = request.path.value()
        )
    }
}