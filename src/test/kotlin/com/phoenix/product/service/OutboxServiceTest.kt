package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.model.OutboxEvent
import com.phoenix.product.repository.model.Product
import io.cloudevents.CloudEvent
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.reactivestreams.Publisher
import org.springframework.kafka.support.SendResult
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
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

    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private lateinit var sampleProduct: Product
    private lateinit var sampleOutboxEvent: OutboxEvent

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)

        // Set required @Value properties
        ReflectionTestUtils.setField(outboxService, "productEventsTopic", "product-events")
        ReflectionTestUtils.setField(outboxService, "applicationName", "product-service")

        sampleProduct = Product(
            id = 1L,
            name = "Test Product",
            description = "Test Description",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue")),
            tags = objectMapper.writeValueAsString(listOf("electronics")),
            version = 1L,
            createdBy = "system",
            createdAt = Instant.now()
        )

        sampleOutboxEvent = OutboxEvent(
            id = 1L,
            aggregateId = "1",
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
        every { outboxRepository.save(any<OutboxEvent>()) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When & Then
        StepVerifier.create(outboxService.publishProductCreatedEvent(sampleProduct))
            .verifyComplete()

        verify { outboxRepository.save(any<OutboxEvent>()) }
        verify { CloudEventWrapper.wrapEvent(
            "com.phoenix.events.product.created",
            "/products",
            any(),
            any()
        )}
    }

    @Test
    fun `publishProductCreatedEvent should throw exception when event creation fails`() {
        // Given
        every { CloudEventWrapper.createEventMetadata() } throws RuntimeException("Event creation failed")

        // When & Then
        StepVerifier.create(outboxService.publishProductCreatedEvent(sampleProduct))
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun `publishProductUpdatedEvent should store event in outbox successfully`() {
        // Given
        every { outboxRepository.save(any<OutboxEvent>()) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When & Then
        StepVerifier.create(outboxService.publishProductUpdatedEvent(sampleProduct))
            .verifyComplete()

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
        every { outboxRepository.save(any<OutboxEvent>()) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        // When & Then
        StepVerifier.create(outboxService.publishProductDeletedEvent(1L, "user"))
            .verifyComplete()

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
        val unprocessedEvents = listOf(sampleOutboxEvent, sampleOutboxEvent.copy(id = 2L))
        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns Flux.fromIterable(unprocessedEvents)
        every { outboxRepository.save(any<OutboxEvent>()) } returns Mono.just(sampleOutboxEvent.copy(processed = true, processedAt = Instant.now()))

        every { CloudEventWrapper.deserializeCloudEvent(any()) } returns mockk<CloudEvent>()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.getCorrelationId(any()) } returns Optional.empty()

        val mockSendResult = mockk<SendResult<String, Any>>()
        val future = CompletableFuture.completedFuture(mockSendResult)
        every { cloudEventPublisher.publishEvent(any(), any()) } returns future

        // When & Then
        StepVerifier.create(outboxService.processOutboxEvents())
            .verifyComplete()

        verify { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
        verify(exactly = 2) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

    @Test
    fun `processOutboxEvents should handle invalid event payload gracefully`() {
        val invalidJson = """{ "not": "a cloud event" }"""
        val invalidEvent = sampleOutboxEvent.copy(eventPayload = invalidJson)

        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns Flux.just(invalidEvent)
        unmockkStatic(CloudEventWrapper::class) // Let the deserialisation fail naturally

        StepVerifier.create(outboxService.processOutboxEvents())
            .verifyComplete()

        verify { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
    }

    @Test
    fun `cleanupProcessedEvents should delete old processed events`() {
        // Given
        val oldDate = Instant.now().minusSeconds(3600)
        val processedEvents = listOf(
            sampleOutboxEvent.copy(processed = true, createdAt = oldDate),
            sampleOutboxEvent.copy(id = 2L, processed = true, createdAt = oldDate)
        )

        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns Flux.fromIterable(processedEvents)
        every { outboxRepository.deleteAll(processedEvents) } returns Mono.empty()

        // When & Then
        StepVerifier.create(outboxService.cleanupProcessedEvents(oldDate))
            .verifyComplete()

        verify { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify { outboxRepository.deleteAll(processedEvents) }
    }

    @Test
    fun `cleanupProcessedEvents should handle empty list gracefully`() {
        // Given
        val oldDate = Instant.now().minusSeconds(3600)
        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns Flux.empty<OutboxEvent>()

        // When & Then
        StepVerifier.create(outboxService.cleanupProcessedEvents(oldDate))
            .verifyComplete()

        verify { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify(exactly = 0) { outboxRepository.deleteAll(any<Publisher<OutboxEvent>>()) }
    }
}