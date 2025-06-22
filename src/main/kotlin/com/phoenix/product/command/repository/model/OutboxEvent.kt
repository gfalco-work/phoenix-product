package com.phoenix.product.command.repository.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.time.Instant
import java.util.*

@Document(collection = "outbox_events")
data class OutboxEvent(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Field("aggregate_id")
    val aggregateId: String,

    @Field("event_type")
    val eventType: String,

    @Field("event_payload")
    val eventPayload: String,

    @Field("processed")
    val processed: Boolean = false,

    @Field("created_at")
    val createdAt: Instant,

    @Field("processed_at")
    val processedAt: Instant? = null,

    @Field("retry_count")
    val retryCount: Int = 0,

    @Field("error_message")
    val errorMessage: String? = null
)