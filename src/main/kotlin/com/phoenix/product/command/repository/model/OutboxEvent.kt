package com.phoenix.product.command.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.util.*

@Document(collection = "outbox_events")
@CompoundIndex(def = "{'processed': 1, 'created_at': 1}")
@CompoundIndex(def = "{'aggregate_id': 1, 'created_at': 1}")
data class OutboxEvent(

    @Id
    val id: String = UUID.randomUUID().toString(),
    val aggregateId: String,
    val eventType: String,
    val eventPayload: String,
    val processed: Boolean = false,
    val processedAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)