# Phoenix Product Service

A reactive microservice for product management built with Kotlin, Spring Boot WebFlux, PostgreSQL, and Kafka.

## Features

- ✅ CRUD Operations: Create, Read, Update, Delete products
- ✅ GraphQL API: Flexible querying with data aggregation from other services
- ✅ Reactive Programming: Built with Spring WebFlux and Kotlin Coroutines
- ✅ Outbox Pattern: Reliable event publishing with transactional guarantees
- ✅ Event Publishing: Events published to Kafka for other services
- ✅ Validation: Input validation with Jakarta Bean Validation
- ✅ Exception Handling: Global exception handling with proper error responses
- ✅ Observability: OpenTelemetry integration for metrics, traces, and logs
- ✅ Security: OAuth2 JWT resource server configuration
- ✅ Testing: Integration tests with Testcontainers

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Admin Web     │───▶│  REST API       │───▶│   Product DB    │
│   (CRUD)        │    │  (CRUD)         │    │  (PostgreSQL)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                                │
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Client App    │───▶│  GraphQL API    │───▶│   Product DB    │
│   (PDP/Search)  │    │  (Aggregation)  │    │  (PostgreSQL)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │                       │
                                │                       │
                                ▼                       ▼
                       ┌─────────────────┐    ┌─────────────────┐
                       │  External APIs  │    │  Outbox Events  │
                       │ (Stock, Price,  │    │ (PostgreSQL)    │
                       │  Promotions)    │    └─────────────────┘
                       └─────────────────┘             │
                                                       ▼
                                              ┌─────────────────┐
                                              │     Kafka       │
                                              │   (Events)      │
                                              └─────────────────┘
                                                       │
                                                       ▼
                                              ┌─────────────────┐
                                              │ Search Service  │
                                              │ & Other         │
                                              │ Consumers       │
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
Go to phoenix-gitops to start the related docker-compose files 
docker-compose up -d

# Verify services are running
docker-compose ps
```

This will start:
- PostgreSQL (port 5432)
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

1. **Transactional Write**: Product and outbox event are saved in the same PostgreSQL transaction
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

The integration tests use Testcontainers to spin up PostgreSQL and Kafka containers automatically.

## Deployment

### Docker Build
```bash
# Build Docker image
docker build -t phoenix-product .

# Run with Docker
docker run -p 8300:8300 \
  -e SPRING_R2DBC_URL=r2dbc:postgresql://host.docker.internal:5432/productdb \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  phoenix-product
```

### Environment Variables
- SPRING_R2DBC_URL: PostgreSQL R2DBC connection string
- SPRING_R2DBC_USERNAME: Database username
- SPRING_R2DBC_PASSWORD: Database password
- SPRING_KAFKA_BOOTSTRAP_SERVERS: Kafka bootstrap servers
- SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: JWT issuer URI

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request
 