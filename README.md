# Phoenix Product Command Service

A CQRS-based microservice for product management built with Kotlin, Spring Boot, MongoDB, and Kafka.

## Features

- ✅ **CRUD Operations**: Create, Read, Update, Delete products
- ✅ **CQRS Pattern**: Command-side implementation with event publishing
- ✅ **Outbox Pattern**: Reliable event publishing with transactional guarantees
- ✅ **Event Sourcing**: Events published to Kafka for projection services
- ✅ **Validation**: Input validation with Jakarta Bean Validation
- ✅ **Exception Handling**: Global exception handling with proper error responses
- ✅ **Observability**: OpenTelemetry integration for metrics, traces, and logs
- ✅ **Security**: OAuth2 JWT resource server configuration
- ✅ **Testing**: Integration tests with Testcontainers

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   REST Client   │───▶│  Command API    │───▶│   Product DB    │
└─────────────────┘    └─────────────────┘    │   (MongoDB)     │
                                │              └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐    ┌─────────────────┐
                       │  Outbox Events  │───▶│     Kafka       │
                       │   (MongoDB)     │    │   (Events)      │
                       └─────────────────┘    └─────────────────┘
                                                       │
                                                       ▼
                                              ┌─────────────────┐
                                              │ Projection      │
                                              │ Services        │
                                              │ (Query Side)    │
                                              └─────────────────┘
```

## Prerequisites

- Java 21+
- Docker & Docker Compose
- Gradle (or use the wrapper)

## Quick Start

### 1. Start Infrastructure

```bash
# Start all infrastructure services
docker-compose up -d

# Verify services are running
docker-compose ps
```

This will start:
- MongoDB (port 27017)
- Kafka & Zookeeper (port 9092)
- Kafka UI (port 8080)
- Jaeger (port 16686)
- Prometheus (port 9090)
- OpenTelemetry Collector (ports 4317, 4318)

### 2. Build and Run the Application

```bash
# Build the application
./gradlew build

# Run the application
./gradlew bootRun
```

The service will be available at: `http://localhost:8300`

### 3. Test the API

#### Create a Product
```bash
curl -X POST http://localhost:8300/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Gaming Laptop",
    "description": "High-performance gaming laptop",
    "category": "Electronics",
    "price": 1299.99,
    "brand": "TechBrand",
    "sku": "LAPTOP-001",
    "specifications": {
      "RAM": "16GB",
      "Storage": "1TB SSD",
      "GPU": "RTX 4060"
    },
    "tags": ["gaming", "laptop", "electronics"]
  }'
```

#### Get a Product
```bash
curl http://localhost:8300/api/v1/products/{productId}
```

#### Update a Product
```bash
curl -X PUT http://localhost:8300/api/v1/products/{productId} \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Gaming Laptop",
    "description": "Updated description",
    "category": "Electronics",
    "price": 1199.99,
    "brand": "TechBrand",
    "sku": "LAPTOP-001-V2",
    "specifications": {
      "RAM": "32GB",
      "Storage": "2TB SSD",
      "GPU": "RTX 4070"
    },
    "tags": ["gaming", "laptop", "electronics", "updated"]
  }'
```

#### Delete a Product
```bash
curl -X DELETE http://localhost:8300/api/v1/products/{productId}
```

## Monitoring & Observability

### Kafka UI
- URL: http://localhost:8080
- Monitor Kafka topics and messages
- View published events: `product.created`, `product.updated`, `product.deleted`

### Jaeger (Distributed Tracing)
- URL: http://localhost:16686
- View request traces across services
- Monitor performance and errors

### Prometheus (Metrics)
- URL: http://localhost:9090
- Query application metrics
- Monitor system health

### Application Health
- Health: http://localhost:8300/actuator/health
- Metrics: http://localhost:8300/actuator/metrics
- Prometheus: http://localhost:8300/actuator/prometheus

## Event Publishing

The service uses the **Outbox Pattern** for reliable event publishing:

1. **Transactional Write**: Product and outbox event are saved in the same MongoDB transaction
2. **Background Publisher**: Scheduled task publishes events from outbox to Kafka
3. **Retry Logic**: Failed events are retried with exponential backoff
4. **Cleanup**: Old processed events are automatically cleaned up

### Event Topics

- `product.created` - Published when a product is created
- `product.updated` - Published when a product is updated
- `product.deleted` - Published when a product is deleted

### Event Schema Example

```json
{
  "eventType": "ProductCreated",
  "productId": "uuid",
  "name": "Product Name",
  "description": "Product Description",
  "category": "Category",
  "price": 99.99,
  "brand": "Brand",
  "sku": "SKU-001",
  "specifications": {"key": "value"},
  "tags": ["tag1", "tag2"],
  "createdBy": "user-id",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

## Testing

### Run Unit Tests
```bash
./gradlew test
```

### Run Integration Tests
```bash
./gradlew integrationTest
```

The integration tests use Testcontainers to spin up MongoDB and Kafka containers automatically.

## Configuration

### Application Properties
Key configuration in `application.yml`:

```yaml
server:
  port: 8300

spring:
  data:
    mongodb:
      uri: mongodb://admin:admin@localhost:27017/productdb
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all  # Ensures durability
      retries: 3
```

### Security
The service is configured as an OAuth2 Resource Server. To disable security for development:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: # comment out or remove
```

## Development

### Project Structure
```
src/main/kotlin/com/phoenix/product/command/
├── api/                    # REST controllers and DTOs
├── config/                 # Configuration classes
├── exception/              # Custom exceptions and handlers
├── repository/             # Data access layer
│   └── model/             # Domain entities
└── service/               # Business logic
```

### Key Components

- **ProductCommandController**: REST API endpoints
- **ProductService**: Business logic and orchestration
- **OutboxService**: Event publishing logic
- **OutboxEventPublisher**: Background event publisher
- **ProductRepository**: Product data access
- **OutboxRepository**: Outbox event data access

## Deployment

### Docker Build
```bash
# Build Docker image
docker build -t phoenix-product-command .

# Run with Docker
docker run -p 8300:8300 \
  -e SPRING_DATA_MONGODB_URI=mongodb://host.docker.internal:27017/productdb \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  phoenix-product-command
```

### Environment Variables
- `SPRING_DATA_MONGODB_URI`: MongoDB connection string
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`: Kafka bootstrap servers
- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`: JWT issuer URI

## Next Steps

1. **Build Query Service**: Create a separate service to consume events and build read models
2. **Add More Events**: Implement product deletion events
3. **Schema Registry**: Use Confluent Schema Registry for better event schema management
4. **Authentication**: Implement proper JWT authentication
5. **API Documentation**: Add OpenAPI/Swagger documentation
6. **Monitoring**: Add custom metrics and alerting
7. **CI/CD**: Set up continuous integration and deployment pipelines

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License.