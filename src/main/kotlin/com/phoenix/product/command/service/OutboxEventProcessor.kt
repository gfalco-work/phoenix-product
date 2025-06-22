package com.phoenix.product.command.service

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Scheduled processor for outbox events
 * Processes unprocessed events and cleans up old processed events
 */
@Component
@ConditionalOnProperty(
    prefix = "phoenix.outbox",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class OutboxEventProcessor(
    private val outboxService: OutboxService
) {

    private val logger = LoggerFactory.getLogger(OutboxEventProcessor::class.java)

    /**
     * Process unprocessed outbox events every 10 seconds
     */
    @Scheduled(fixedDelay = 10000) // 10 seconds
    fun processOutboxEvents() {
        try {
            logger.debug("Starting outbox event processing")
            outboxService.processOutboxEvents()
            logger.debug("Completed outbox event processing")
        } catch (e: Exception) {
            logger.error("Error during outbox event processing", e)
        }
    }

    /**
     * Cleanup processed events older than 7 days, runs daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
    fun cleanupOldEvents() {
        try {
            val cutoffTime = Instant.now().minus(7, ChronoUnit.DAYS)
            logger.info("Starting cleanup of processed events older than {}", cutoffTime)
            outboxService.cleanupProcessedEvents(cutoffTime)
            logger.info("Completed cleanup of old processed events")
        } catch (e: Exception) {
            logger.error("Error during outbox event cleanup", e)
        }
    }
}