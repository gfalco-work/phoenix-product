package com.phoenix.product.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("outbox_events")
data class OutboxEvent(
    @Id
    val id: Long? = null,
    val aggregateId: String,
    val eventType: String,
    val eventPayload: String,
    val processed: Boolean = false,
    val processedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)