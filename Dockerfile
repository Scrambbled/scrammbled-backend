# ---------- Build stage ----------
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom first to leverage Docker cache
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy source code
COPY src ./src

# Build the application
RUN mvn -B -q package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy only the built JAR
COPY --from=build /app/target/*.jar app.jar

# Optional: expose port if it's a server app
EXPOSE 8080
EXPOSE 9999

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
