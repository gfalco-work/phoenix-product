package com.phoenix.product.command.service

import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.events.product.DeletionType
import com.phoenix.events.product.ProductCreatedEventData
import com.phoenix.events.product.ProductDeletedEventData
import com.phoenix.events.product.ProductUpdatedEventData
import com.phoenix.product.command.repository.OutboxRepository
import com.phoenix.product.command.repository.model.OutboxEvent
import com.phoenix.product.command.repository.model.Product
import io.cloudevents.CloudEvent
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class OutboxService(
    private val outboxRepository: OutboxRepository,
    private val cloudEventPublisher: CloudEventPublisher
) {

    private val log = KotlinLogging.logger {}

    @Value("\${phoenix.events.topics.product-events:product-events}")
    private lateinit var productEventsTopic: String

    @Value("\${spring.application.name:product-command-service}")
    private lateinit var applicationName: String

    /**
     * Publishes a ProductCreated event using the Phoenix Events library
     */
    fun publishProductCreatedEvent(product: Product) {
        try {
            // Create Avro event data with all required fields
            val eventData = ProductCreatedEventData.newBuilder()
                .setProductId(product.id)
                .setName(product.name)
                .setDescription(product.description ?: "")
                .setCategory(product.category)
                .setPrice(product.price)
                .setBrand(product.brand ?: "")
                .setSku(product.sku)
                .setSpecifications(product.specifications ?: emptyMap())
                .setTags(product.tags ?: emptyList())
                .setVersion(product.version)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()

            // Create CloudEvent using enhanced wrapper
            val cloudEvent = CloudEventWrapper.wrapEvent(
                "com.phoenix.events.product.created",
                "/products",
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(product.id, "ProductCreated", cloudEvent)

            log.info("ProductCreated event stored in outbox for product: {}", product.id)

        } catch (e: Exception) {
            log.error("Failed to create ProductCreated event for product: {}", product.id, e)
            throw RuntimeException("Failed to create ProductCreated event", e)
        }
    }

    /**
     * Publishes a ProductUpdated event using the Phoenix Events library
     */
    fun publishProductUpdatedEvent(product: Product) {
        try {
            // Create Avro event data
            val eventData = ProductUpdatedEventData.newBuilder()
                .setProductId(product.id)
                .setName(product.name)
                .setDescription(product.description ?: "")
                .setCategory(product.category)
                .setPrice(product.price)
                .setBrand(product.brand ?: "")
                .setSku(product.sku)
                .setSpecifications(product.specifications ?: emptyMap())
                .setTags(product.tags ?: emptyList())
                .setVersion(product.version)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()

            // Create CloudEvent using enhanced wrapper
            val cloudEvent = CloudEventWrapper.wrapEvent(
                "com.phoenix.events.product.updated",
                "/products",
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(product.id, "ProductUpdated", cloudEvent)

            log.info("ProductUpdated event stored in outbox for product: {}", product.id)

        } catch (e: Exception) {
            log.error("Failed to create ProductUpdated event for product: {}", product.id, e)
            throw RuntimeException("Failed to create ProductUpdated event", e)
        }
    }

    /**
     * Publishes a ProductDeleted event using the Phoenix Events library
     */
    fun publishProductDeletedEvent(productId: String, deletedBy: String) {
        try {
            // Create proper Avro event data for deletion
            val eventData = ProductDeletedEventData.newBuilder()
                .setProductId(productId)
                .setDeletedBy(deletedBy)
                .setDeletionType(DeletionType.HARD_DELETE)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()

            // Create CloudEvent using enhanced wrapper
            val cloudEvent = CloudEventWrapper.wrapEvent(
                "com.phoenix.events.product.deleted",
                "/products",
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(productId, "ProductDeleted", cloudEvent)

            log.info("ProductDeleted event stored in outbox for product: {}", productId)

        } catch (e: Exception) {
            log.error("Failed to create ProductDeleted event for product: {}", productId, e)
            throw RuntimeException("Failed to create ProductDeleted event", e)
        }
    }

    /**
     * Processes unprocessed events from the outbox and publishes them to Kafka
     * This method should be called by a scheduled job or event processor
     */
    @Transactional
        fun processOutboxEvents() {
            val unprocessedEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc()

            log.info("Processing {} unprocessed outbox events", unprocessedEvents.size)

            for (event in unprocessedEvents) {
                try {
                    processOutboxEvent(event)
                } catch (e: Exception) {
                    log.error("Failed to process outbox event: {}", event.id, e)
                }
            }
        }

    /**
     * Processes a single outbox event
     */
    private fun processOutboxEvent(outboxEvent: OutboxEvent) {
        try {
            // Validate the event payload before processing
            if (!isValidEventPayload(outboxEvent.eventPayload)) {
                throw IllegalArgumentException("Invalid event payload for event: ${outboxEvent.id}")
            }

            // Deserialize the CloudEvent from the payload using enhanced wrapper
            val cloudEvent = CloudEventWrapper.deserializeCloudEvent(outboxEvent.eventPayload)

            // Validate the deserialized CloudEvent
            if (!CloudEventWrapper.isValidCloudEvent(cloudEvent)) {
                throw IllegalArgumentException("Invalid CloudEvent structure for event: ${outboxEvent.id}")
            }

            // Log correlation ID for tracing
            CloudEventWrapper.getCorrelationId(cloudEvent).ifPresent { correlationId ->
                log.debug("Processing event with correlation ID: {}", correlationId)
            }

            // Publish to Kafka using CloudEventPublisher
            val future = cloudEventPublisher.publishEvent(productEventsTopic, cloudEvent)

            // Handle the result asynchronously
            future.whenComplete { result, throwable ->
                if (throwable == null) {
                    // Mark as processed on success
                    markEventAsProcessed(outboxEvent)
                    log.info("Successfully processed outbox event: {} for aggregate: {}",
                        outboxEvent.id, outboxEvent.aggregateId)
                } else {
                    log.error("Failed to publish outbox event: {}", outboxEvent.id, throwable)
                    // Event remains unprocessed for potential manual review
                }
            }

        } catch (e: Exception) {
            log.error("Failed to process outbox event: {}", outboxEvent.id, e)
            throw e
        }
    }

    /**
     * Stores a CloudEvent in the outbox for transactional safety
     */
    private fun storeEventInOutbox(aggregateId: String, eventType: String, cloudEvent: CloudEvent) {
        try {
            // Validate CloudEvent before storing
            if (!CloudEventWrapper.isValidCloudEvent(cloudEvent)) {
                throw IllegalArgumentException("Invalid CloudEvent structure")
            }

            val serializedEvent = CloudEventWrapper.serializeCloudEvent(cloudEvent)

            val outboxEvent = OutboxEvent(
                aggregateId = aggregateId,
                eventType = eventType,
                eventPayload = serializedEvent,
                createdAt = Instant.now()
            )

            outboxRepository.save(outboxEvent)

        } catch (e: Exception) {
            log.error("Failed to store event in outbox for aggregate: {}", aggregateId, e)
            throw RuntimeException("Failed to store event in outbox", e)
        }
    }

    /**
     * Validates event payload before processing
     */
    private fun isValidEventPayload(payload: String?): Boolean {
        return !payload.isNullOrBlank() && payload.trim().startsWith("{") && payload.trim().endsWith("}")
    }

    /**
     * Marks an outbox event as processed
     */
    private fun markEventAsProcessed(outboxEvent: OutboxEvent) {
        try {
            val updatedEvent = outboxEvent.copy(
                processed = true,
                processedAt = Instant.now()
            )
            outboxRepository.save(updatedEvent)
        } catch (e: Exception) {
            log.error("Failed to mark event as processed: {}", outboxEvent.id, e)
        }
    }

    /**
     * Cleanup processed outbox events older than specified duration
     */
    @Transactional
    fun cleanupProcessedEvents(olderThan: Instant) {
        try {
            val eventsToDelete = outboxRepository.findByProcessedTrueAndCreatedAtBefore(olderThan)
            if (eventsToDelete.isNotEmpty()) {
                outboxRepository.deleteAll(eventsToDelete)
                log.info("Cleaned up {} processed outbox events older than {}", eventsToDelete.size, olderThan)
            } else {
                log.debug("No processed events found for cleanup before {}", olderThan)
            }
        } catch (e: Exception) {
            log.error("Failed to cleanup processed events", e)
        }
    }
}