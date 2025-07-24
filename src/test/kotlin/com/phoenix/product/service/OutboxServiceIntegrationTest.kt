package com.phoenix.product.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.phoenix.events.cloudevents.CloudEventPublisher
import com.phoenix.product.repository.OutboxRepository
import com.phoenix.product.repository.model.OutboxEvent
import com.phoenix.product.repository.model.Product
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboxServiceIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")
            .withDatabaseName("productdb")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgresContainer.host}:${postgresContainer.firstMappedPort}/${postgresContainer.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgresContainer.username }
            registry.add("spring.r2dbc.password") { postgresContainer.password }

            // Add Flyway configuration for test container
            registry.add("spring.flyway.url") {
                "jdbc:postgresql://${postgresContainer.host}:${postgresContainer.firstMappedPort}/${postgresContainer.databaseName}"
            }
            registry.add("spring.flyway.user") { postgresContainer.username }
            registry.add("spring.flyway.password") { postgresContainer.password }
        }
    }

    @Autowired
    private lateinit var outboxService: OutboxService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @MockkBean
    private lateinit var cloudEventPublisher: CloudEventPublisher

    private lateinit var testProduct: Product

    @BeforeEach
    fun setUp() {
        // Clean up repository before each test
        outboxRepository.deleteAll().block()

        // Setup test product
        testProduct = Product(
            id = 1L,
            name = "Test Product",
            description = "A test product",
            category = "Electronics",
            price = 99.99,
            brand = "TestBrand",
            sku = "TEST-SKU-001",
            specifications = objectMapper.writeValueAsString(mapOf("color" to "blue", "size" to "medium")),
            tags = objectMapper.writeValueAsString(listOf("test", "electronics")),
            createdBy = "test-user",
            createdAt = Instant.now(),
            version = 1L
        )

        // Setup mock for CloudEventPublisher
        every { cloudEventPublisher.publishEvent(any(), any()) } returns
                CompletableFuture.completedFuture(null)
    }

    @Test
    fun `should store ProductCreated event in outbox and process it successfully`() {
        // When - Publish the event
        outboxService.publishProductCreatedEvent(testProduct).block()

        // Then - Verify event is stored in outbox
        val outboxEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, outboxEvents.size)

        val outboxEvent = outboxEvents.first()
        assertEquals(testProduct.id.toString(), outboxEvent.aggregateId)
        assertEquals("ProductCreated", outboxEvent.eventType)
        assertFalse(outboxEvent.processed)
        assertTrue(outboxEvent.eventPayload.isNotBlank())
        assertNull(outboxEvent.processedAt)

        // When - Process outbox events
        outboxService.processOutboxEvents().block()

        // Allow async processing to complete
        Thread.sleep(200)

        // Then - Verify CloudEventPublisher was called
        verify(exactly = 1) {
            cloudEventPublisher.publishEvent("product-events", any())
        }

        // Verify event is marked as processed
        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, processedEvents.size)
        assertTrue(processedEvents.first().processed)
        assertNotNull(processedEvents.first().processedAt)
    }

    @Test
    fun `should store ProductUpdated event in outbox and process it successfully`() {
        // Given - Updated product
        val updatedProduct = testProduct.copy(
            name = "Updated Test Product",
            price = 149.99,
            version = 2L
        )

        // When - Publish the event
        outboxService.publishProductUpdatedEvent(updatedProduct).block()

        // Then - Verify event is stored in outbox
        val outboxEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, outboxEvents.size)

        val outboxEvent = outboxEvents.first()
        assertEquals(updatedProduct.id.toString(), outboxEvent.aggregateId)
        assertEquals("ProductUpdated", outboxEvent.eventType)
        assertFalse(outboxEvent.processed)

        // When - Process outbox events
        outboxService.processOutboxEvents().block()
        Thread.sleep(200)

        // Then - Verify processing
        verify(exactly = 1) {
            cloudEventPublisher.publishEvent("product-events", any())
        }

        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertTrue(processedEvents.first().processed)
    }

    @Test
    fun `should store ProductDeleted event in outbox and process it successfully`() {
        // When - Publish the delete event
        outboxService.publishProductDeletedEvent(testProduct.id!!, "admin-user").block()

        // Then - Verify event is stored in outbox
        val outboxEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, outboxEvents.size)

        val outboxEvent = outboxEvents.first()
        assertEquals(testProduct.id.toString(), outboxEvent.aggregateId)
        assertEquals("ProductDeleted", outboxEvent.eventType)
        assertFalse(outboxEvent.processed)

        // When - Process outbox events
        outboxService.processOutboxEvents().block()
        Thread.sleep(200)

        // Then - Verify processing
        verify(exactly = 1) {
            cloudEventPublisher.publishEvent("product-events", any())
        }

        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertTrue(processedEvents.first().processed)
    }

    @Test
    fun `should process multiple events in correct order`() {
        // Given - Multiple products
        val product1 = testProduct.copy(id = 2L)
        val product2 = testProduct.copy(id = 3L)
        val product3 = testProduct.copy(id = 4L)

        // When - Publish events with slight delay to ensure ordering
        outboxService.publishProductCreatedEvent(product1).block()
        Thread.sleep(10)
        outboxService.publishProductCreatedEvent(product2).block()
        Thread.sleep(10)
        outboxService.publishProductCreatedEvent(product3).block()

        // Then - Verify all events are stored
        val outboxEvents = outboxRepository.findAll().collectList().block()!!.sortedBy { it.createdAt }
        assertEquals(3, outboxEvents.size)
        assertEquals("2", outboxEvents[0].aggregateId)
        assertEquals("3", outboxEvents[1].aggregateId)
        assertEquals("4", outboxEvents[2].aggregateId)

        // When - Process all events
        outboxService.processOutboxEvents().block()
        Thread.sleep(300)

        // Then - Verify all events were processed
        verify(exactly = 3) {
            cloudEventPublisher.publishEvent("product-events", any())
        }

        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(3, processedEvents.size)
        assertTrue(processedEvents.all { it.processed })
    }

    @Test
    fun `should handle publishing failure gracefully`() {
        // Given - CloudEventPublisher that fails
        every { cloudEventPublisher.publishEvent(any(), any()) } returns
                CompletableFuture.failedFuture(RuntimeException("Kafka is down"))

        // When - Publish and process event
        outboxService.publishProductCreatedEvent(testProduct).block()
        outboxService.processOutboxEvents().block()
        Thread.sleep(200)

        // Then - Event should remain unprocessed
        val outboxEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, outboxEvents.size)
        assertFalse(outboxEvents.first().processed)
        assertNull(outboxEvents.first().processedAt)

        // Verify publish attempt was made
        verify(exactly = 1) {
            cloudEventPublisher.publishEvent("product-events", any())
        }
    }

    @Test
    fun `should handle mixed success and failure scenarios`() {
        // Given - Publisher that fails for ProductCreated but succeeds for ProductDeleted
        every { cloudEventPublisher.publishEvent("product-events", match {
            it.type == "com.phoenix.events.product.created"
        }) } returns CompletableFuture.failedFuture(RuntimeException("Network error"))

        every { cloudEventPublisher.publishEvent("product-events", match {
            it.type == "com.phoenix.events.product.deleted"
        }) } returns CompletableFuture.completedFuture(null)

        val product1 = testProduct.copy(id = 5L)
        val product2 = testProduct.copy(id = 6L)

        // When - Publish different types of events
        outboxService.publishProductCreatedEvent(product2).block()
        outboxService.publishProductDeletedEvent(product1.id!!, "admin").block()

        outboxService.processOutboxEvents().block()
        Thread.sleep(1000)

        // Then - Check mixed results
        val outboxEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(2, outboxEvents.size)

        val createdEvent = outboxEvents.find { it.eventType == "ProductCreated" }
        val deletedEvent = outboxEvents.find { it.eventType == "ProductDeleted" }

        assertNotNull(createdEvent)
        assertNotNull(deletedEvent)

        assertFalse(createdEvent.processed, "ProductCreated event should remain unprocessed due to failure")
        assertTrue(deletedEvent.processed, "ProductDeleted event should be processed successfully")
    }

    @Test
    fun `should cleanup processed events older than specified time`() {
        // Given - Events with different timestamps
        val oldEvent = OutboxEvent(
            aggregateId = "7",
            eventType = "ProductCreated",
            eventPayload = """{"test": "data"}""",
            processed = true,
            processedAt = Instant.now().minus(2, ChronoUnit.DAYS),
            createdAt = Instant.now().minus(2, ChronoUnit.DAYS)
        )

        val recentEvent = OutboxEvent(
            aggregateId = "8",
            eventType = "ProductCreated",
            eventPayload = """{"test": "data"}""",
            processed = true,
            processedAt = Instant.now().minus(1, ChronoUnit.HOURS),
            createdAt = Instant.now().minus(1, ChronoUnit.HOURS)
        )

        val unprocessedEvent = OutboxEvent(
            aggregateId = "9",
            eventType = "ProductCreated",
            eventPayload = """{"test": "data"}""",
            processed = false,
            createdAt = Instant.now().minus(3, ChronoUnit.DAYS)
        )

        outboxRepository.saveAll(listOf(oldEvent, recentEvent, unprocessedEvent)).collectList().block()

        // When - Cleanup events older than 1 day
        val cutoffTime = Instant.now().minus(1, ChronoUnit.DAYS)
        outboxService.cleanupProcessedEvents(cutoffTime).block()

        // Then - Only old processed event should be deleted
        val remainingEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(2, remainingEvents.size)

        val remainingIds = remainingEvents.map { it.aggregateId }
        assertTrue(remainingIds.contains("8"))
        assertTrue(remainingIds.contains("9"))
        assertFalse(remainingIds.contains("7"))
    }

    @Test
    fun `should not process events with invalid payload`() {
        // Given - Manually create event with invalid payload
        val invalidEvent = OutboxEvent(
            aggregateId = "10",
            eventType = "ProductCreated",
            eventPayload = "invalid-json-payload",
            createdAt = Instant.now()
        )
        outboxRepository.save(invalidEvent).block()

        // When - Try to process events
        outboxService.processOutboxEvents().block()
        Thread.sleep(200)

        // Then - Event should remain unprocessed
        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertFalse(events.first().processed)

        // Publisher should not be called
        verify(exactly = 0) {
            cloudEventPublisher.publishEvent(any(), any())
        }
    }

    @Test
    fun `should handle empty outbox gracefully`() {
        // Given - Empty outbox (setUp already clears it)
        assertEquals(0, outboxRepository.findAll().collectList().block()!!.size)

        // When - Process events
        assertDoesNotThrow {
            outboxService.processOutboxEvents().block()
        }

        // Then - No exceptions and no publisher calls
        verify(exactly = 0) {
            cloudEventPublisher.publishEvent(any(), any())
        }
    }

    @Test
    fun `should handle product with minimal data`() {
        // Given - Product with minimal required fields (null optionals)
        val minimalProduct = Product(
            id = 11L,
            name = "Minimal Product",
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

        // When - Publish event
        assertDoesNotThrow {
            outboxService.publishProductCreatedEvent(minimalProduct).block()
        }

        // Then - Event should be stored successfully
        val events = outboxRepository.findAll().collectList().block()!!
        assertEquals(1, events.size)
        assertEquals("11", events.first().aggregateId)

        // When - Process the event
        outboxService.processOutboxEvents().block()
        Thread.sleep(200)

        // Then - Should be processed successfully despite null fields
        verify(exactly = 1) {
            cloudEventPublisher.publishEvent("product-events", any())
        }

        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertTrue(processedEvents.first().processed)
    }

    @Test
    fun `should handle sequential processing without concurrency issues`() {
        // Given - Multiple events
        val products = (12..14).map {
            testProduct.copy(id = it.toLong())
        }

        // When - Publish all events
        products.forEach { product ->
            outboxService.publishProductCreatedEvent(product).block()
        }

        // Then - All events stored
        assertEquals(3, outboxRepository.findAll().collectList().block()!!.size)

        // When - Process events multiple times (simulating multiple scheduler runs)
        repeat(3) {
            outboxService.processOutboxEvents().block()
            Thread.sleep(100)
        }

        // Then - All events should be processed exactly once
        val processedEvents = outboxRepository.findAll().collectList().block()!!
        assertEquals(3, processedEvents.size)
        assertTrue(processedEvents.all { it.processed })

        // Each event should be processed exactly once despite multiple processor runs
        verify(exactly = 3) {
            cloudEventPublisher.publishEvent("product-events", any())
        }
    }
}