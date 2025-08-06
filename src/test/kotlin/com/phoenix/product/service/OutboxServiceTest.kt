package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.events.cloudevents.CloudEventWrapper
import com.phoenix.observability.tracing.services.ObservabilityService
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.model.OutboxEvent
import com.phoenix.product.repository.model.Product
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.reactivestreams.Publisher
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.function.Function

@ExtendWith(MockKExtension::class)
class OutboxServiceTest {

    @MockK
    private lateinit var outboxRepository: OutboxRepository

    @MockK
    private lateinit var cloudEventPublisher: CloudEventPublisher

    @MockK(relaxed = true)
    private lateinit var observabilityService: ObservabilityService

    @InjectMockKs
    private lateinit var outboxService: OutboxService

    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val fixedInstant = Instant.parse("2023-01-01T00:00:00Z")

    private lateinit var sampleProduct: Product
    private lateinit var sampleOutboxEvent: OutboxEvent

    @BeforeEach
    fun setUp() {
        ReflectionTestUtils.setField(outboxService, "productEventsTopic", "product-events")

        val opSlot = slot<Function<Any, Mono<Any>>>()
        every {
            observabilityService.wrapMono<Any, Any>(any(), any(), any(), capture(opSlot), any())
        } answers {
            val input = arg<Any>(2)
            opSlot.captured.apply(input)
        }

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
            createdAt = fixedInstant
        )

        sampleOutboxEvent = OutboxEvent(
            id = 1L,
            aggregateId = "1",
            eventType = "ProductCreated",
            eventPayload = """{"test": "payload"}""",
            processed = false,
            createdAt = fixedInstant
        )

        mockkStatic(CloudEventWrapper::class)
    }

    @AfterEach
    fun tearDown() = unmockkStatic(CloudEventWrapper::class)

    @Test
    fun `publishProductCreatedEvent should store event in outbox successfully`() {
        val savedSlot = slot<OutboxEvent>()
        every { outboxRepository.save(capture(savedSlot)) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        StepVerifier.create(outboxService.publishProductCreatedEvent(sampleProduct))
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.save(any()) }
        verifyOrder {
            CloudEventWrapper.wrapEvent(
                "com.phoenix.events.product.created",
                "/products",
                any(),
                any()
            )
            outboxRepository.save(any())
        }
        assertThat(savedSlot.captured.eventType).isEqualTo("ProductCreated")
        assertThat(savedSlot.captured.aggregateId).isEqualTo(sampleProduct.id.toString())
    }

    @Test
    fun `publishProductCreatedEvent should throw exception when event creation fails`() {
        every { CloudEventWrapper.createEventMetadata() } throws RuntimeException("Event creation failed")

        StepVerifier.create(outboxService.publishProductCreatedEvent(sampleProduct))
            .expectError(RuntimeException::class.java)
            .verify()

        verify(exactly = 0) { outboxRepository.save(any()) }
    }

    @Test
    fun `publishProductUpdatedEvent should store event in outbox successfully`() {
        val savedSlot = slot<OutboxEvent>()
        every { outboxRepository.save(capture(savedSlot)) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        StepVerifier.create(outboxService.publishProductUpdatedEvent(sampleProduct))
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.save(any()) }
        assertThat(savedSlot.captured.eventType).isEqualTo("ProductUpdated")
    }

    @Test
    fun `publishProductDeletedEvent should store event in outbox successfully`() {
        val savedSlot = slot<OutboxEvent>()
        every { outboxRepository.save(capture(savedSlot)) } returns Mono.just(sampleOutboxEvent)
        every { CloudEventWrapper.createEventMetadata() } returns mockk()
        every { CloudEventWrapper.wrapEvent(any(), any(), any(), any()) } returns mockk()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.serializeCloudEvent(any()) } returns """{"test": "payload"}"""

        StepVerifier.create(outboxService.publishProductDeletedEvent(1L, "user"))
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.save(any()) }
        assertThat(savedSlot.captured.eventType).isEqualTo("ProductDeleted")
        assertThat(savedSlot.captured.aggregateId).isEqualTo("1")
    }

    @Test
    fun `processOutboxEvents should process all unprocessed events`() {
        val unprocessedEvents = listOf(sampleOutboxEvent, sampleOutboxEvent.copy(id = 2L))
        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns Flux.fromIterable(unprocessedEvents)
        every { outboxRepository.save(any()) } returns Mono.just(sampleOutboxEvent.copy(processed = true, processedAt = fixedInstant))
        every { CloudEventWrapper.deserializeCloudEvent(any()) } returns mockk()
        every { CloudEventWrapper.isValidCloudEvent(any()) } returns true
        every { CloudEventWrapper.getCorrelationId(any()) } returns Optional.empty()
        every { cloudEventPublisher.publishEvent(any(), any()) } returns CompletableFuture.completedFuture(mockk())

        StepVerifier.create(outboxService.processOutboxEvents())
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
        verify(exactly = 2) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

    @Test
    fun `processOutboxEvents should handle invalid event payload gracefully`() {
        val invalidJson = """{ "not": "a cloud event" }"""
        val invalidEvent = sampleOutboxEvent.copy(eventPayload = invalidJson)
        every { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() } returns Flux.just(invalidEvent)
        unmockkStatic(CloudEventWrapper::class)

        StepVerifier.create(outboxService.processOutboxEvents())
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.findByProcessedFalseOrderByCreatedAtAsc() }
    }

    @Test
    fun `cleanupProcessedEvents should delete old processed events`() {
        val oldDate = fixedInstant.minusSeconds(3600)
        val processedEvents = listOf(
            sampleOutboxEvent.copy(processed = true, createdAt = oldDate),
            sampleOutboxEvent.copy(id = 2L, processed = true, createdAt = oldDate)
        )
        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns Flux.fromIterable(processedEvents)
        every { outboxRepository.deleteAll(processedEvents) } returns Mono.empty()

        StepVerifier.create(outboxService.cleanupProcessedEvents(oldDate))
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify(exactly = 1) { outboxRepository.deleteAll(processedEvents) }
    }

    @Test
    fun `cleanupProcessedEvents should handle empty list gracefully`() {
        val oldDate = fixedInstant.minusSeconds(3600)
        every { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) } returns Flux.empty()

        StepVerifier.create(outboxService.cleanupProcessedEvents(oldDate))
            .verifyComplete()

        verify(exactly = 1) { outboxRepository.findByProcessedTrueAndCreatedAtBefore(oldDate) }
        verify(exactly = 0) { outboxRepository.deleteAll(any<Publisher<OutboxEvent>>()) }
    }
}
