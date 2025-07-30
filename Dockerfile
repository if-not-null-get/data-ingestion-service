FROM openjdk:21-jdk-slim

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew build -x test

RUN cp build/libs/*.jar app.jar
RUN mkdir logs

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]