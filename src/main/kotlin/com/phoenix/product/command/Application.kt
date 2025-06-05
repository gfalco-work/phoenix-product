package com.phoenix.product.command

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.phoenix"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}