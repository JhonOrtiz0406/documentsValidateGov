# ============ Stage 1: Build ============
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

# Copy Gradle wrapper and project files
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle settings.gradle main.gradle gradle.properties lombok.config ./

# Copy subproject build files
COPY applications/ applications/
COPY domain/ domain/
COPY infrastructure/ infrastructure/

# Build the application
RUN chmod +x gradlew && ./gradlew clean build -x test -x pitest --no-daemon

# ============ Stage 2: Run ============
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Install Tesseract OCR 5 + Spanish language data for PDF PIN extraction
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-spa

# Resolve tessdata path: Alpine places it under /usr/share/tessdata
RUN TESSDATA=$(tesseract --list-langs 2>&1 | grep "tessdata" | head -1 | sed 's/.*(\(.*\)).*/\1/' || true) && \
    echo "Tessdata: $TESSDATA" && \
    tesseract --list-langs 2>&1

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy built jar
COPY --from=builder /app/applications/app-service/build/libs/ScaffoldQuickStart.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

ENV TESSDATA_PREFIX="/usr/share/tessdata"
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
