FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Atlas requires TLS 1.2+; helps avoid handshake issues on some hosts
ENV JAVA_TOOL_OPTIONS="-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

