# Build stage: compile and package the ingestor with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy POM and download dependencies (cached unless pom changes)
COPY pom.xml .
RUN mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency -B -q

# Copy sources and build JAR
COPY src ./src
RUN mvn package -DskipTests -B -q

# Runtime stage: run AnnalsIngestor
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/ingestor-1.0-SNAPSHOT.jar .
COPY --from=builder /build/target/dependency ./dependency

# Configure at runtime (mount Annals CSV data at ANNALS_DATA_FOLDER)
ENV FROST_BASE_URL=""
ENV ANNALS_DATA_FOLDER="/data"
ENV ANNALS_LOCAL_FOLDER="/data/processed"

ENTRYPOINT ["java", "-cp", "/app/ingestor-1.0-SNAPSHOT.jar:/app/dependency/*", "eu.flora.essi.ingestor.annals.AnnalsIngestor"]
