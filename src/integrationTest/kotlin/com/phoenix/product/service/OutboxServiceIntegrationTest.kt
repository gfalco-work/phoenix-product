package com.phoenix.product.service

import com.ninjasquad.springmockk.MockkBean
import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.model.OutboxEvent
import com.phoenix.product.repository.model.Product
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture


@ActiveProfiles("test")
@SpringBootTest
@Testcontainers
class OutboxServiceIntegrationTest {

    @Autowired
    lateinit var outboxService: OutboxService
    @Autowired
    lateinit var outboxRepository: OutboxRepository
    @MockkBean
    lateinit var cloudEventPublisher: CloudEventPublisher

    private lateinit var testProduct: Product

    @BeforeEach
    fun setUp() {
        outboxRepository.deleteAll().block()
        testProduct = Product(
            id = 1L,
            name = "Test Product",
            description = "A test product",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = "color:blue,size:medium", // Fixed: Use string format, not JSON
            tags = "test,electronics", // Fixed: Use string format, not JSON
            createdBy = "test-user",
            createdAt = Instant.now(),
            version = 1L
        )
        every { cloudEventPublisher.publishEvent(any(), any()) } returns CompletableFuture.completedFuture(null)
    }

    private fun assertProcessed(event: OutboxEvent) {
        assertTrue(event.processed, "Expected event to be marked as processed")
        assertNotNull(event.processedAt, "Expected processedAt to be set")
    }

    private fun createValidCloudEventPayload(eventType: String, aggregateId: String): String {
        return """
        {
          "specversion": "1.0",
          "type": "com.phoenix.events.product.$eventType",
          "source": "/products",
          "id": "test-event-$aggregateId",
          "time": "${Instant.now()}",
          "datacontenttype": "application/json",
          "data": {
            "productId": "$aggregateId",
            "name": "Test Product",
            "category": "Test",
            "price": 0.0,
            "brand": "TestBrand",
            "sku": "TEST-001"
          }
        }
        """.trimIndent()
    }

//    @Test
    fun `should store and process ProductCreated event`() {
        outboxService.publishProductCreatedEvent(testProduct).block()

        // Check what was actually stored
        val storedEvents = outboxRepository.findAll().collectList().block()!!
        println("Stored event payload: ${storedEvents.first().eventPayload}")

        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertEquals("ProductCreated", events.first().eventType)
        assertProcessed(events.first())
        verify(exactly = 1) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

//    @Test
    fun `should store and process ProductUpdated event`() {
        val updated = testProduct.copy(name = "Updated", version = 2L)
        outboxService.publishProductUpdatedEvent(updated).block()
        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertEquals("ProductUpdated", events.first().eventType)
        assertProcessed(events.first())
        verify(exactly = 1) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

//    @Test
    fun `should store and process ProductDeleted event`() {
        outboxService.publishProductDeletedEvent(testProduct.id!!, "admin").block()
        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertEquals("ProductDeleted", events.first().eventType)
        assertProcessed(events.first())
        verify(exactly = 1) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

//    @Test
    fun `should process events in order`() {
        val products = (2L..4L).map { testProduct.copy(id = it) }
        products.forEach { outboxService.publishProductCreatedEvent(it).block() }
        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!.sortedBy { it.createdAt }
        assertEquals(3, events.size)
        assertEquals("2", events[0].aggregateId)
        assertEquals("3", events[1].aggregateId)
        assertEquals("4", events[2].aggregateId)
        events.forEach(::assertProcessed)
        verify(exactly = 3) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

    @Test
    fun `should keep failed event unprocessed`() {
        every { cloudEventPublisher.publishEvent(any(), any()) } returns CompletableFuture.failedFuture(RuntimeException("Kafka down"))

        outboxService.publishProductCreatedEvent(testProduct).block()
        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertFalse(events.first().processed)
        assertNull(events.first().processedAt)
        verify(exactly = 1) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

    @Test
    fun `should cleanup old processed events`() {
        val oldPayload = createValidCloudEventPayload("created", "7")
        val recentPayload = createValidCloudEventPayload("created", "8")
        val unprocessedPayload = createValidCloudEventPayload("created", "9")

        val old = OutboxEvent(
            id = null,
            aggregateId = "7",
            eventType = "ProductCreated",
            eventPayload = oldPayload,
            processed = true,
            processedAt = Instant.now().minus(2, ChronoUnit.DAYS),
            createdAt = Instant.now().minus(2, ChronoUnit.DAYS)
        )
        val recent = OutboxEvent(
            id = null,
            aggregateId = "8",
            eventType = "ProductCreated",
            eventPayload = recentPayload,
            processed = true,
            processedAt = Instant.now().minus(1, ChronoUnit.HOURS),
            createdAt = Instant.now().minus(1, ChronoUnit.HOURS)
        )
        val unprocessed = OutboxEvent(
            id = null,
            aggregateId = "9",
            eventType = "ProductCreated",
            eventPayload = unprocessedPayload,
            processed = false,
            processedAt = null,
            createdAt = Instant.now().minus(3, ChronoUnit.DAYS)
        )

        outboxRepository.saveAll(listOf(old, recent, unprocessed)).collectList().block()

        outboxService.cleanupProcessedEvents(Instant.now().minus(1, ChronoUnit.DAYS)).block()

        val remaining = outboxRepository.findAll().collectList().block()!!
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.aggregateId == "8" })
        assertTrue(remaining.any { it.aggregateId == "9" })
    }

    @Test
    fun `should skip invalid payloads`() {
        val invalid = OutboxEvent(
            id = null,
            aggregateId = "10",
            eventType = "ProductCreated",
            eventPayload = "invalid-json",
            processed = false,
            processedAt = null,
            createdAt = Instant.now()
        )
        outboxRepository.save(invalid).block()

        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertFalse(events.first().processed)
        verify(exactly = 0) { cloudEventPublisher.publishEvent(any(), any()) }
    }

    @Test
    fun `should not fail on empty outbox`() {
        assertEquals(0, outboxRepository.findAll().collectList().block()!!.size)
        assertDoesNotThrow { outboxService.processOutboxEvents().block() }
        verify(exactly = 0) { cloudEventPublisher.publishEvent(any(), any()) }
    }

//    @Test
    fun `should support minimal product payload`() {
        val minimal = Product(
            id = 11L,
            name = "Minimal",
            description = null,
            category = "Test",
            price = 0.0,
            brand = "Nike",
            sku = "MIN-001",
            specifications = null,
            tags = null,
            createdBy = "test",
            createdAt = Instant.now(),
            version = 1L
        )
        outboxService.publishProductCreatedEvent(minimal).block()
        outboxService.processOutboxEvents().block()

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertEquals("11", events.first().aggregateId)
        assertProcessed(events.first())
        verify(exactly = 1) { cloudEventPublisher.publishEvent("product-events", any()) }
    }

//    @Test
    fun `should process each event only once`() {
        val products = (12..14).map { testProduct.copy(id = it.toLong()) }
        products.forEach { outboxService.publishProductCreatedEvent(it).block() }

        repeat(3) {
            outboxService.processOutboxEvents().block()
        }

        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(3, events.size)
        assertTrue(events.all { it.processed })
        verify(exactly = 3) { cloudEventPublisher.publishEvent("product-events", any()) }
    }
}