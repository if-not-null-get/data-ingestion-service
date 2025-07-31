FROM openjdk:21-jdk-slim

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew build -x test -x integrationTest

RUN cp build/libs/data-ingestion-service-1.0.0.jar app.jar
RUN mkdir logs

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]