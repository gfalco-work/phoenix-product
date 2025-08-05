package com.phoenix.product.repository

import com.phoenix.product.repository.model.OutboxEvent
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import java.time.Instant

@Repository
interface OutboxRepository : R2dbcRepository<OutboxEvent, Long> {
    fun findByProcessedFalseOrderByCreatedAtAsc(): Flux<OutboxEvent>
    fun findByProcessedTrueAndCreatedAtBefore(cutoffTime: Instant): Flux<OutboxEvent>
}