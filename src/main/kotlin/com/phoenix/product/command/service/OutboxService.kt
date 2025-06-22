package com.phoenix.product.command.service

import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.events.common.EventMetadata
import com.phoenix.events.product.DeletionType
import com.phoenix.events.product.ProductCreatedEventData
import com.phoenix.events.product.ProductDeletedEventData
import com.phoenix.events.product.ProductUpdatedEventData
import com.phoenix.product.command.repository.OutboxRepository
import com.phoenix.product.command.repository.model.OutboxEvent
import com.phoenix.product.command.repository.model.Product
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
@Transactional
class OutboxService(
    private val outboxRepository: OutboxRepository,
    private val cloudEventPublisher: CloudEventPublisher
) {

    private val logger = LoggerFactory.getLogger(OutboxService::class.java)

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
                .setPrice(product.price.toDouble())
                .setBrand(product.brand ?: "")
                .setSku(product.sku)
                .setSpecifications(product.specifications ?: emptyMap())
                .setTags(product.tags ?: emptyList())
                .setVersion(product.version)
                .setMetadata(createEventMetadata("ProductCreated"))
                .build()

            // Create CloudEvent
            val cloudEvent = CloudEventWrapper.wrapProductCreatedEvent(
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(product.id, "ProductCreated", cloudEvent)

            logger.info("ProductCreated event stored in outbox for product: {}", product.id)

        } catch (e: Exception) {
            logger.error("Failed to create ProductCreated event for product: {}", product.id, e)
            throw RuntimeException("Failed to create ProductCreated event", e)
        }
    }

    /**
     * Publishes a ProductUpdated event using the Phoenix Events library
     */
    fun publishProductUpdatedEvent(product: Product) {
        try {
            // Create Avro event data - fix BigDecimal conversion
            val eventData = ProductUpdatedEventData.newBuilder()
                .setProductId(product.id)
                .setName(product.name)
                .setDescription(product.description ?: "")
                .setCategory(product.category)
                .setPrice(product.price.toDouble())
                .setBrand(product.brand ?: "")
                .setSku(product.sku)
                .setSpecifications(product.specifications ?: emptyMap())
                .setTags(product.tags ?: emptyList())
                .setVersion(product.version)
                .setMetadata(createEventMetadata("ProductUpdated"))
                .build()

            // Create CloudEvent
            val cloudEvent = CloudEventWrapper.wrapProductUpdatedEvent(
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(product.id, "ProductUpdated", cloudEvent)

            logger.info("ProductUpdated event stored in outbox for product: {}", product.id)

        } catch (e: Exception) {
            logger.error("Failed to create ProductUpdated event for product: {}", product.id, e)
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
                .setMetadata(createEventMetadata("ProductDeleted"))
                .build()

            // Create CloudEvent using the wrapper
            val cloudEvent = CloudEventWrapper.wrapProductDeletedEvent(
                eventData,
                eventData.metadata
            )

            // Store in outbox for transactional safety
            storeEventInOutbox(productId, "ProductDeleted", cloudEvent)

            logger.info("ProductDeleted event stored in outbox for product: {}", productId)

        } catch (e: Exception) {
            logger.error("Failed to create ProductDeleted event for product: {}", productId, e)
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

        logger.info("Processing {} unprocessed outbox events", unprocessedEvents.size)

        for (event in unprocessedEvents) {
            try {
                processOutboxEvent(event)
            } catch (e: Exception) {
                logger.error("Failed to process outbox event: {}", event.id, e)
                handleEventProcessingFailure(event, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Processes a single outbox event
     */
    private fun processOutboxEvent(outboxEvent: OutboxEvent) {
        try {
            // Deserialize the CloudEvent from the payload
            val cloudEvent = deserializeCloudEvent(outboxEvent.eventPayload)

            // Publish to Kafka using CloudEventPublisher
            val future = cloudEventPublisher.publishEvent(productEventsTopic, cloudEvent)

            // Handle the result asynchronously
            future.whenComplete { result, throwable ->
                if (throwable == null) {
                    // Mark as processed on success
                    markEventAsProcessed(outboxEvent)
                    logger.info("Successfully processed outbox event: {} for aggregate: {}",
                        outboxEvent.id, outboxEvent.aggregateId)
                } else {
                    logger.error("Failed to publish outbox event: {}", outboxEvent.id, throwable)
                    handleEventProcessingFailure(outboxEvent, throwable.message ?: "Publication failed")
                }
            }

        } catch (e: Exception) {
            logger.error("Failed to process outbox event: {}", outboxEvent.id, e)
            throw e
        }
    }

    /**
     * Stores a CloudEvent in the outbox for transactional safety
     */
    private fun storeEventInOutbox(aggregateId: String, eventType: String, cloudEvent: CloudEvent) {
        val outboxEvent = OutboxEvent(
            aggregateId = aggregateId,
            eventType = eventType,
            eventPayload = serializeCloudEvent(cloudEvent),
            createdAt = Instant.now()
        )

        outboxRepository.save(outboxEvent)
    }

    /**
     * Creates event metadata with correlation tracking
     */
    private fun createEventMetadata(eventType: String): EventMetadata {
        return EventMetadata.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setTimestamp(Instant.now().toEpochMilli())
            .setSource(applicationName)
            .setVersion("1.0")
            .setCorrelationId(UUID.randomUUID().toString())
            .build()
    }

    /**
     * Serializes a CloudEvent to JSON string for storage using CloudEvents JSON format
     */
    private fun serializeCloudEvent(cloudEvent: CloudEvent): String {
        try {
            return String(JsonFormat().serialize(cloudEvent))
        } catch (e: Exception) {
            logger.error("Failed to serialize CloudEvent: {}", cloudEvent.id, e)
            // Fallback to manual serialization
            return buildManualCloudEventJson(cloudEvent)
        }
    }

    /**
     * Manual CloudEvent JSON serialization as fallback
     */
    private fun buildManualCloudEventJson(cloudEvent: CloudEvent): String {
        val extensionsJson = if (cloudEvent.extensionNames.isNotEmpty()) {
            cloudEvent.extensionNames.joinToString(",") { name ->
                val value = cloudEvent.getExtension(name)
                "\"$name\":\"$value\""
            }
        } else {
            ""
        }

        val dataJson = cloudEvent.data?.let {
            "\"data\":\"${Base64.getEncoder().encodeToString(it.toBytes())}\""
        } ?: ""

        return """
            {
                "id": "${cloudEvent.id}",
                "type": "${cloudEvent.type}",
                "source": "${cloudEvent.source}",
                "specversion": "${cloudEvent.specVersion}",
                "time": "${cloudEvent.time}",
                "datacontenttype": "${cloudEvent.dataContentType ?: ""}",
                $dataJson${if (dataJson.isNotEmpty() && extensionsJson.isNotEmpty()) "," else ""}
                $extensionsJson
            }
        """.trimIndent()
    }

    /**
     * Deserializes a CloudEvent from JSON string using CloudEvents JSON format
     */
    private fun deserializeCloudEvent(payload: String): CloudEvent {
        try {
            return JsonFormat().deserialize(payload.toByteArray())
        } catch (e: Exception) {
            logger.error("Failed to deserialize CloudEvent from payload", e)
            throw RuntimeException("Failed to deserialize CloudEvent", e)
        }
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
            logger.error("Failed to mark event as processed: {}", outboxEvent.id, e)
        }
    }

    /**
     * Handles event processing failures by updating retry count and error message
     */
    private fun handleEventProcessingFailure(outboxEvent: OutboxEvent, errorMessage: String) {
        try {
            val updatedEvent = outboxEvent.copy(
                retryCount = outboxEvent.retryCount + 1,
                errorMessage = errorMessage
            )
            outboxRepository.save(updatedEvent)
        } catch (e: Exception) {
            logger.error("Failed to update failed event: {}", outboxEvent.id, e)
        }
    }

    /**
     * Cleanup processed outbox events older than specified duration
     */
    @Transactional
    fun cleanupProcessedEvents(olderThan: Instant) {
        try {
            val eventsToDelete = outboxRepository.findByProcessedTrueAndCreatedAtBefore(olderThan)
            outboxRepository.deleteAll(eventsToDelete)
            logger.info("Cleaned up {} processed outbox events older than {}", eventsToDelete.size, olderThan)
        } catch (e: Exception) {
            logger.error("Failed to cleanup processed events", e)
        }
    }
}