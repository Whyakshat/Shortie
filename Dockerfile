# Pure Java 21 JDK Build Stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY src ./src
RUN mkdir -p out && javac -d out src/com/urlshortener/*.java

# Lightweight JRE Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/out ./out
EXPOSE 8080
ENV PORT=8080
CMD ["java", "-Dport=8080", "-cp", "out", "com.urlshortener.Main"]
