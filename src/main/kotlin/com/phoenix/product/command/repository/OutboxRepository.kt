package com.phoenix.product.command.repository

import com.phoenix.product.command.repository.model.OutboxEvent
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OutboxRepository : MongoRepository<OutboxEvent, String> {

    // Find unprocessed events
    fun findByProcessedFalseOrderByCreatedAtAsc(): List<OutboxEvent>

    // Find events created before a specific time (for cleanup)
    fun findByProcessedTrueAndCreatedAtBefore(cutoffTime: Instant): List<OutboxEvent>

}