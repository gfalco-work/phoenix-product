package com.phoenix.product.command.exception

class ProductConcurrentModificationException(message: String) : RuntimeException(message)

class ProductNotFoundException(message: String) : RuntimeException(message)