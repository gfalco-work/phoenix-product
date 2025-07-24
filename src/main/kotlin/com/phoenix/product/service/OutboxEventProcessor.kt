import com.phoenix.product.service.OutboxService
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
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

    private val log = KotlinLogging.logger {}

    @Value("\${phoenix.outbox.cleanup.retention-days:7}")
    private val retentionDays: Long = 7

    /**
     * Process unprocessed outbox events every 10 seconds
     */
    @Scheduled(fixedDelayString = "\${phoenix.outbox.processing.interval:10000}")
    fun processOutboxEvents() {
        try {
            log.debug("Starting outbox event processing")
            outboxService.processOutboxEvents()
                .doOnSuccess { log.debug("Completed outbox event processing") }
                .doOnError { e -> log.error("Error during outbox event processing", e) }
                .subscribe()
        } catch (e: Exception) {
            log.error("Error during outbox event processing", e)
        }
    }

    /**
     * Cleanup processed events older than 7 days, runs daily at 2 AM
     */
    @Scheduled(cron = "\${phoenix.outbox.cleanup.cron:0 0 2 * * *}")
    fun cleanupOldEvents() {
        try {
            val cutoffTime = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
            log.info("Starting cleanup of processed events older than {}", cutoffTime)
            outboxService.cleanupProcessedEvents(cutoffTime)
                .doOnSuccess { log.info("Completed cleanup of old processed events") }
                .doOnError { e -> log.error("Error during outbox event cleanup", e) }
                .subscribe()
        } catch (e: Exception) {
            log.error("Error during outbox event cleanup", e)
        }
    }
}