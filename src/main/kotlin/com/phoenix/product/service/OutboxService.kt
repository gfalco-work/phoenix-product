package com.phoenix.product.service

import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.events.product.DeletionType
import com.phoenix.events.product.ProductCreatedEventData
import com.phoenix.events.product.ProductDeletedEventData
import com.phoenix.events.product.ProductUpdatedEventData
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.model.OutboxEvent
import com.phoenix.product.repository.model.Product
import io.cloudevents.CloudEvent
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.Instant

@Service
@Transactional
class OutboxService(
    private val outboxRepository: OutboxRepository,
    private val cloudEventPublisher: CloudEventPublisher,
) {

    private val log = KotlinLogging.logger {}

    @Value("\${phoenix.events.topics.product-events:product-events}")
    private lateinit var productEventsTopic: String

    /**
     * Publishes a ProductCreated event using the Phoenix Events library
     */
    fun publishProductCreatedEvent(product: Product): Mono<Void> {
        return Mono.fromCallable {
            ProductCreatedEventData.newBuilder()
                .setProductId(product.id?.toString() ?: "")
                .setName(product.name)
                .setDescription(product.description ?: "")
                .setCategory(product.category)
                .setPrice(product.price)
                .setBrand(product.brand)
                .setSku(product.sku)
                .setSpecifications(parseSpecifications(product.specifications))
                .setTags(parseTags(product.tags))
                .setVersion(product.version)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()
        }
            .flatMap { eventData ->
                val cloudEvent = CloudEventWrapper.wrapEvent(
                    "com.phoenix.events.product.created",
                    "/products",
                    eventData,
                    eventData.metadata
                )
                storeEventInOutbox(product.id?.toString() ?: "", "ProductCreated", cloudEvent)
            }
            .doOnSuccess { log.info("ProductCreated event stored in outbox for product: {}", product.id) }
            .onErrorMap { e ->
                log.error("Failed to create ProductCreated event for product: {}", product.id, e)
                RuntimeException("Failed to create ProductCreated event", e)
            }
            .then()
    }

    /**
     * Publishes a ProductUpdated event using the Phoenix Events library
     */
    fun publishProductUpdatedEvent(product: Product): Mono<Void> {
        return Mono.fromCallable {
            ProductUpdatedEventData.newBuilder()
                .setProductId(product.id?.toString() ?: "")
                .setName(product.name)
                .setDescription(product.description ?: "")
                .setCategory(product.category)
                .setPrice(product.price)
                .setBrand(product.brand)
                .setSku(product.sku)
                .setSpecifications(parseSpecifications(product.specifications))
                .setTags(parseTags(product.tags))
                .setVersion(product.version)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()
        }
            .flatMap { eventData ->
                val cloudEvent = CloudEventWrapper.wrapEvent(
                    "com.phoenix.events.product.updated",
                    "/products",
                    eventData,
                    eventData.metadata
                )
                storeEventInOutbox(product.id?.toString() ?: "", "ProductUpdated", cloudEvent)
            }
            .doOnSuccess { log.info("ProductUpdated event stored in outbox for product: {}", product.id) }
            .onErrorMap { e ->
                log.error("Failed to create ProductUpdated event for product: {}", product.id, e)
                RuntimeException("Failed to create ProductUpdated event", e)
            }
            .then()
    }

    /**
     * Publishes a ProductDeleted event using the Phoenix Events library
     */
    fun publishProductDeletedEvent(productId: Long, deletedBy: String): Mono<Void> {
        return Mono.fromCallable {
            ProductDeletedEventData.newBuilder()
                .setProductId(productId.toString())
                .setDeletedBy(deletedBy)
                .setDeletionType(DeletionType.HARD_DELETE)
                .setMetadata(CloudEventWrapper.createEventMetadata())
                .build()
        }
            .flatMap { eventData ->
                val cloudEvent = CloudEventWrapper.wrapEvent(
                    "com.phoenix.events.product.deleted",
                    "/products",
                    eventData,
                    eventData.metadata
                )
                storeEventInOutbox(productId.toString(), "ProductDeleted", cloudEvent)
            }
            .doOnSuccess { log.info("ProductDeleted event stored in outbox for product: {}", productId) }
            .onErrorMap { e ->
                log.error("Failed to create ProductDeleted event for product: {}", productId, e)
                RuntimeException("Failed to create ProductDeleted event", e)
            }
            .then()
    }

    /**
     * Processes unprocessed events from the outbox and publishes them to Kafka
     * This method should be called by a scheduled job or event processor
     */
    fun processOutboxEvents(): Mono<Void> {
        return outboxRepository.findByProcessedFalseOrderByCreatedAtAsc()
            .flatMap { event ->
                processOutboxEvent(event)
                    .onErrorResume { error ->
                        log.warn("Skipping invalid event {}: {}", event.id, error.message)
                        Mono.empty()
                    }
            }
            .then()
    }

    /**
     * Processes a single outbox event
     */
    private fun processOutboxEvent(outboxEvent: OutboxEvent): Mono<Void> {
        log.info("Starting to process outbox event: {}", outboxEvent.id)
        return validateEventPayload(outboxEvent.eventPayload)
            .flatMap {
                Mono.fromCallable { CloudEventWrapper.deserializeCloudEvent(outboxEvent.eventPayload) }
            }
            .flatMap { cloudEvent ->
                if (!CloudEventWrapper.isValidCloudEvent(cloudEvent)) {
                    Mono.error(IllegalArgumentException("Invalid CloudEvent structure for event: ${outboxEvent.id}"))
                } else {
                    Mono.just(cloudEvent)
                }
            }
            .doOnNext { cloudEvent ->
                CloudEventWrapper.getCorrelationId(cloudEvent).ifPresent { correlationId ->
                    log.debug("Processing event with correlation ID: {}", correlationId)
                }
            }
            .flatMap { cloudEvent ->
                log.info("About to publish event: {}", outboxEvent.id)
                Mono.fromFuture(cloudEventPublisher.publishEvent(productEventsTopic, cloudEvent))
                    .doOnSuccess {
                        log.info("Successfully published outbox event: {} for aggregate: {}",
                            outboxEvent.id, outboxEvent.aggregateId)
                    }
                    .then(Mono.defer {
                        log.info("About to mark event as processed: {}", outboxEvent.id)
                        markEventAsProcessed(outboxEvent)
                    })
            }
            .doOnError { e ->
                log.error("Failed to process outbox event: {}", outboxEvent.id, e)
            }
    }

    /**
     * Stores a CloudEvent in the outbox for transactional safety
     */
    private fun storeEventInOutbox(aggregateId: String, eventType: String, cloudEvent: CloudEvent): Mono<OutboxEvent> {
        return Mono.fromCallable {
            if (!CloudEventWrapper.isValidCloudEvent(cloudEvent)) {
                throw IllegalArgumentException("Invalid CloudEvent structure")
            }

            val serializedEvent = CloudEventWrapper.serializeCloudEvent(cloudEvent)

            OutboxEvent(
                aggregateId = aggregateId,
                eventType = eventType,
                eventPayload = serializedEvent,
                createdAt = Instant.now()
            )
        }
            .flatMap { outboxEvent ->
                outboxRepository.save(outboxEvent)
            }
            .onErrorMap { e ->
                log.error("Failed to store event in outbox for aggregate: {}", aggregateId, e)
                RuntimeException("Failed to store event in outbox", e)
            }
    }

    /**
     * Validates event payload before processing
     */
    private fun validateEventPayload(payload: String?): Mono<String> {
        return if (payload.isNullOrBlank() || !payload.trim().startsWith("{") || !payload.trim().endsWith("}")) {
            Mono.error(IllegalArgumentException("Invalid event payload format"))
        } else {
            Mono.just(payload)
        }
    }

    /**
     * Marks an outbox event as processed
     */
    private fun markEventAsProcessed(outboxEvent: OutboxEvent): Mono<Void> {
        val id = outboxEvent.id
        return if (id != null) {
            val now = Instant.now()
            val updatedEvent = outboxEvent.copy(processed = true, processedAt = now)
            outboxRepository.save(updatedEvent)
                .doOnNext { saved ->
                    log.info("Successfully marked outbox event {} as processed", saved.id)
                }
                .then()
        } else {
            log.error("Cannot mark event as processed: missing ID")
            Mono.error(IllegalStateException("Cannot mark event as processed: missing ID"))
        }
    }



    /**
     * Cleanup processed outbox events older than specified duration
     */
    @Transactional
    fun cleanupProcessedEvents(olderThan: Instant): Mono<Void> {
        return outboxRepository.findByProcessedTrueAndCreatedAtBefore(olderThan)
            .collectList()
            .flatMap { eventsToDelete: List<OutboxEvent> ->
                if (eventsToDelete.isNotEmpty()) {
                    outboxRepository.deleteAll(eventsToDelete)
                        .doOnSuccess {
                            log.info("Cleaned up {} processed outbox events older than {}", eventsToDelete.size, olderThan)
                        }
                        .then()
                } else {
                    log.debug("No processed events found for cleanup before {}", olderThan)
                    Mono.empty()
                }
            }
            .onErrorMap { e ->
                log.error("Failed to cleanup processed events", e)
                e
            }
    }

    private fun parseSpecifications(specifications: String?): MutableMap<String, String> {
        return specifications?.let { spec ->
            try {
                // Basic parsing - split by comma and then by colon
                spec.split(",")
                    .mapNotNull { pair ->
                        val parts = pair.split(":")
                        if (parts.size == 2) {
                            parts[0].trim() to parts[1].trim()
                        } else null
                    }
                    .toMap()
                    .toMutableMap()
            } catch (e: Exception) {
                log.warn("Failed to parse specifications: {}", spec, e)
                mutableMapOf()
            }
        } ?: mutableMapOf()
    }

    private fun parseTags(tags: String?): List<String> {
        // Assuming tags is stored as comma-separated string
        return tags?.split(",")?.map { it.trim() } ?: emptyList()
    }
}