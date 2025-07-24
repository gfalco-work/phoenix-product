package com.phoenix.product.exception

class ProductConcurrentModificationException(message: String) : RuntimeException(message)

class ProductNotFoundException(message: String) : RuntimeException(message)