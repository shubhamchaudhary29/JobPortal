# Use an official OpenJDK runtime as a parent image
FROM openjdk:17-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the project's build file and source code
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Run the jar file
CMD ["java", "-jar", "target/backend-0.0.1-SNAPSHOT.jar"]
