package com.phoenix.product.command.exception

class ProductAlreadyExistsException(message: String) : RuntimeException(message)

class ProductNotFoundException(message: String) : RuntimeException(message)