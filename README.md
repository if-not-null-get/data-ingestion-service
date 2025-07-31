# ConflictRadar Data Ingestion Service

## What is this?

This is a **microservice** that reads news from RSS feeds. It finds articles about conflicts and wars. It sends this data to other services for analysis.

## Why do we need this service?

**Main job**: Get news data into the system.

**What it does**:
- Reads RSS feeds from BBC, Reuters, CNN
- Finds articles about conflicts (war, terrorism, violence)
- Removes duplicate articles
- Scores how dangerous each article is (risk score)
- Sends events to Kafka for other services

## Architecture Overview

```
RSS Sources → Data Ingestion Service → Kafka → Other Services
    ↓              ↓                     ↓
  BBC News    Redis Cache         Processing Service
  Reuters     Risk Analysis       Alert Engine  
  CNN         Deduplication       Dashboard
```

## How it works

1. **Scheduler runs every 5 minutes**
2. **Downloads RSS feeds** from news sources
3. **Checks Redis** - skip articles we already processed
4. **Analyzes text** - looks for conflict keywords
5. **Calculates risk score** - how dangerous is this news?
6. **Sends events to Kafka** - other services read these events

## Technologies Used

### Core Framework
- **Spring Boot** - Main application framework
- **Java 21** - Programming language

### Data Storage
- **Redis** - Cache for duplicate detection
- **Kafka** - Event streaming between services

### Keywords Analysis
- **Simple text matching** - finds words like "war", "terrorism", "attack"
- **Risk scoring** - calculates danger level (0.0 to 1.0)

### RSS Processing
- **Rome Tools** - Parses RSS/XML feeds
- **HTTP Client** - Downloads RSS data

### DevOps
- **Docker** - Containerization
- **Docker Compose** - Multi-container setup

## Project Structure

```
src/
├── main/java/
│   ├── api/           # REST controllers
│   ├── domain/        # Business logic  
│   └── infrastructure/ # Kafka, Redis configs
├── test/java/         # Unit tests
└── integrationTest/   # Integration tests
```

## Why Separate Unit and Integration Tests?

**Unit Tests** (`src/test/`):
- Test single classes
- Use mocks instead of real Redis/Kafka
- Run fast (< 1 second)
- Run in CI/CD pipeline

**Integration Tests** (`src/integrationTest/`):
- Test whole application
- Use real Redis/Kafka containers
- Run slow (30+ seconds)
- Need Docker/Testcontainers to run

**Why separate?**
- Unit tests run fast during development
- Integration tests run only before deployment
- CI/CD can run them at different stages
- Gradle can run them independently

## API Endpoints

### Health Check
```
GET /api/v1/rss/health
```
Returns: Service status

### Manual RSS Processing
```
GET /api/v1/rss/feeds?url=https://feeds.bbci.co.uk/news/world/rss.xml
```
Returns: Processed articles with risk scores

### Available Sources
```
GET /api/v1/rss/sources
```
Returns: List of configured RSS sources

### Scheduled Status
```
GET /api/v1/rss/scheduled/status
```
Returns: Status of automatic processing

## How to Run

### Prerequisites
- Docker
- Docker Compose

### Start Everything
```bash
docker-compose up --build
```

### Test API
```bash
curl http://localhost:8080/api/v1/rss/health
```

## Configuration

### RSS Sources
Edit `application.yml` to add new sources:
```yaml
# Currently hardcoded in ScheduledRssService.java
# TODO: Move to configuration file
```

### Kafka Topics
- `news-ingested` - New articles processed
- `high-risk-detected` - Dangerous articles found
- `batch-processed` - Processing statistics

## What Happens Next?

This service sends events to Kafka. Other services read these events:

1. **Processing Service** - Advanced text analysis
2. **Alert Engine** - Sends notifications for high-risk articles
3. **Dashboard** - Shows data to users
4. **Graph Analytics** - Finds connections between events

## Development

### Run Tests
```bash
# Unit tests only
./gradlew test

# All tests (needs Docker)
./gradlew check
```

### Local Development
```bash
# Start dependencies
docker-compose up redis kafka zookeeper

# Run application locally
./gradlew bootRun
```

## Architecture Decisions

### Why Not AWS (for now)?
- **Development phase** - AWS pricing is confusing and unpredictable
- **Unexpected bills** - Free tier limits are unclear
- **Self-hosted stack** - Docker gives us full control
- **Production ready** - Can migrate to AWS SQS, SNS, S3 later
- **Same patterns** - Kafka → SQS, Redis → ElastiCache, MinIO → S3

### Why Kafka?
- **Event streaming** between services
- **Replay capability** - can reprocess old events
- **Scaling** - multiple services read same events

### Why Redis?
- **Fast duplicate detection**
- **TTL** - automatically removes old entries
- **Simple** - just key-value storage

### Why Simple Risk Analysis?
- **Prototype phase** - keyword matching is fast
- **Good enough** - finds most conflict articles
- **Extensible** - can add ML later

## Monitoring

### Logs
```bash
docker-compose logs -f app
```

### Kafka Topics
```bash
# List topics
docker exec conflictradar-kafka kafka-topics --list --bootstrap-server localhost:9092

# Read messages
docker exec conflictradar-kafka kafka-console-consumer --topic news-ingested --bootstrap-server localhost:9092
```

### Redis Data
```bash
# Connect to Redis
docker exec -it conflictradar-redis redis-cli

# See processed articles
KEYS rss:article:*
```

## Production Readiness

### ✅ Done
- Automatic RSS processing
- Duplicate detection
- Event streaming
- Docker deployment
- Error handling (basic)

### ❌ TODO
- Configuration externalization
- Advanced monitoring
- Better error recovery
- API documentation
- Performance optimization

---

**Simple. Fast. Reliable.**