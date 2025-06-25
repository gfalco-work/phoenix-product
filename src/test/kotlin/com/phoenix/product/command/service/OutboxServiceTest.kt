package com.phoenix.product.command.service

import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.product.command.repository.OutboxRepository
import com.phoenix.product.command.repository.model.OutboxEvent
import com.phoenix.product.command.repository.model.Product
import io.cloudevents.CloudEvent
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.support.SendResult
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

@ExtendWith(MockKExtension::class)
class OutboxServiceTest {

    @MockK
    private lateinit var outboxRepository: OutboxRepository

    @MockK
    private lateinit var cloudEventPublisher: CloudEventPublisher

    @InjectMockKs
    private lateinit var outboxService: OutboxService

    private lateinit var sampleProduct: Product
    private lateinit var sampleOutboxEvent: OutboxEvent

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)

        // Set required @Value properties
        ReflectionTestUtils.setField(outboxService, "productEventsTopic", "product-events")
        ReflectionTestUtils.setField(outboxService, "applicationName", "product-command-service")

        sampleProduct = Product(
            id = "test-id",
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = mapOf("color" to "blue"),
            tags = listOf("electronics"),
            version = 1L,
            createdBy = "system",
            createdAt = Instant.now()
        )

        sampleOutboxEvent = OutboxEvent(
            id = "event-id",
            aggregateId = "test-id",
            eventType = "ProductCreated",
            eventPayload = """{"test": "payload"}""",
            processed = false,
            createdAt = Instant.now()
        )

        mockkStatic(CloudEventWrapper::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(CloudEventWrapper::class)
    }

    @Test
    fun `publishProductCreatedEvent should store event in outbox successfully`() {
        // Given
        every { outboxRepository.save(any<OutboxEvent>()) } returns sampleOutboxEvent

        mockkStatic(CloudEventWrapper::class)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When
        outboxService.publishProductCreatedEvent(sampleProduct)

        // Then
        verify { outboxRepository.save(any<OutboxEvent>()) }
        verify { CloudEventWrapper.wrapEvent(
            "com.phoenix.events.product.created",
            "/products",
            any(),
            any()
        )}
        unmockkStatic(CloudEventWrapper::class)
    }

    @Test
    fun `publishProductCreatedEvent should throw exception when event creation fails`() {
        // Given
        every { CloudEventWrapper.createEventMetadata() } throws RuntimeException("Event creation failed")

        // When & Then
        assertThrows<RuntimeException> {
            outboxService.publishProductCreatedEvent(sampleProduct)
        }
    }

    @Test
    fun `publishProductUpdatedEvent should store event in outbox successfully`() {
        // Given
        every { outboxRepository.save(any<OutboxEvent>()) } returns sampleOutboxEvent

        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When
        outboxService.publishProductUpdatedEvent(sampleProduct)

        // Then
        verify { outboxRepository.save(any<OutboxEvent>()) }
        verify { CloudEventWrapper.wrapEvent(
            "com.phoenix.events.product.updated",
            "/products",
            any(),
            any()
        )}
    }

    @Test
    fun `publishProductDeletedEvent should store event in outbox successfully`() {
        // Given
        every { outboxRepository.save(any<OutboxEvent>()) } returns sampleOutboxEvent

        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When
        outboxService.publishProductDeletedEvent("test-id", "user")

        // Then
        verify { outboxRepository.save(any<OutboxEvent>()) }
        verify { CloudEventWrapper.wrapEvent(
            "com.phoenix.events.product.deleted",
            "/products",
            any(),
            any()
        )}
    }

    @Test
    fun `processOutboxEvents should process all unprocessed events`() {
        // Given
        val unprocessedEvents = listOf(sampleOutboxEvent, sampleOutboxEvent.copy(id = "event-2"))
        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns unprocessedEvents
        every { outboxRepository.save(any<OutboxEvent>()) } returns sampleOutboxEvent

        every { CloudEventWrapper.deserializeCloudEvent(any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.getCorrelationId(any()) } returns Optional.empty()

        // Create a properly typed CompletableFuture with correct generic type
        val mockSendResult = mockk<SendResult<String, Any>>()
        val future = CompletableFuture.completedFuture(mockSendResult)
        every { cloudEventPublisher.publishEvent(any(), any()) } returns future

        // When
        outboxService.processOutboxEvents()

        // Then
        verify { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
        verify(exactly = 2) { cloudEventPublisher.publishEvent("product-events", any()) }

    }

    @Test
    fun `processOutboxEvents should handle invalid event payload gracefully`() {
        // Given
        val invalidEvent = sampleOutboxEvent.copy(eventPayload = "invalid json")
        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns listOf(invalidEvent)

        // When
        outboxService.processOutboxEvents()

        // Then
        verify { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
        verify(exactly = 0) { cloudEventPublisher.publishEvent(any(), any()) }
    }

    @Test
    fun `cleanupProcessedEvents should delete old processed events`() {
        // Given
        val oldDate = Instant.now().minusSeconds(3600)
        val processedEvents = listOf(
            sampleOutboxEvent.copy(processed = true, createdAt = oldDate),
            sampleOutboxEvent.copy(id = "event-2", processed = true, createdAt = oldDate)
        )

        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns processedEvents
        every { outboxRepository.deleteAll(processedEvents) } just Runs

        // When
        outboxService.cleanupProcessedEvents(oldDate)

        // Then
        verify { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify { outboxRepository.deleteAll(processedEvents) }
    }

    @Test
    fun `cleanupProcessedEvents should handle empty list gracefully`() {
        // Given
        val oldDate = Instant.now().minusSeconds(3600)
        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns emptyList()

        // When
        outboxService.cleanupProcessedEvents(oldDate)

        // Then
        verify { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify(exactly = 0) { outboxRepository.deleteAll(any()) }
    }
}