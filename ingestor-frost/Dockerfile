# Build stage: compile and package the ingestor with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy POM and download dependencies (cached unless pom changes)
COPY pom.xml .
RUN mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency -B -q

# Copy sources and build JAR
COPY src ./src
RUN mvn package -DskipTests -B -q

# Runtime stage: run AnnalsIngestor (Hub one-shot or compose override)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache bash curl git ca-certificates tar gzip unzip \
  && update-ca-certificates

COPY --from=builder /build/target/ingestor-1.0-SNAPSHOT.jar .
COPY --from=builder /build/target/dependency ./dependency
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Runtime configuration (override at docker run / compose time)
ENV FROST_BASE_URL=""
ENV ANNALS_DATA_FOLDER="/data"
ENV ANNALS_LOCAL_FOLDER="/data/processed"
# Hub one-shot defaults (compose files override these for split prepare/upload)
ENV ANNALS_PREPARE="true"
ENV ANNALS_MAP="true"
ENV ANNALS_UPLOAD="true"
ENV ANNALS_MAX_OBSERVATIONS_PER_BATCH="1000"
ENV ANNALS_UPLOAD_PARALLELISM="16"
ENV ANNALS_UPLOAD_STRATEGY="DETERMINISTIC_ID"
# Data source when /data is empty (GitHub sparse clone, or set ANNALS_DATA_URL)
ENV ANNALS_DATA_REPO="https://github.com/ISPRA-BIO-ACAS/annali-idrologici.git"
ENV ANNALS_DATA_REF="main"
ENV ANNALS_DATA_URL=""
ENV ANNALS_SKIP_DOWNLOAD="false"
ENV ANNALS_DOWNLOAD_FORCE="false"

VOLUME ["/data"]

ENTRYPOINT ["/app/entrypoint.sh"]
