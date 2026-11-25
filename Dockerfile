# One Dockerfile for every service: pass the module to build.
#   docker build --build-arg SERVICE=wallet-service -t payments/wallet-service .
#
# Three stages, so the final image holds a JRE and the app, and nothing else:
# no JDK, no Maven, no source code.

# --- 1. Build the service's jar, with Maven's downloads cached between builds.
# sharing=locked: Compose builds the five images in parallel, and two Maven
# runs writing one repository at once trip over each other.
FROM eclipse-temurin:21-jdk-noble AS build
ARG SERVICE
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY wallet-service/pom.xml wallet-service/
COPY auth-service/pom.xml auth-service/
COPY transfer-service/pom.xml transfer-service/
COPY notification-service/pom.xml notification-service/
COPY gateway/pom.xml gateway/
COPY ${SERVICE}/src ${SERVICE}/src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q -pl ${SERVICE} -am package -DskipTests \
 && cp ${SERVICE}/target/${SERVICE}-*.jar /app.jar

# --- 2. Split the jar into layers: dependencies change rarely, our code often.
# Docker caches each layer, so a code change ships only the small last one.
FROM eclipse-temurin:21-jdk-noble AS layers
WORKDIR /layers
COPY --from=build /app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- 3. The runtime image: a JRE, a non-root user, the layers.
FROM eclipse-temurin:21-jre-noble
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER app
# Size the heap from the container's memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# exec form: the JVM is PID 1 and receives SIGTERM, so graceful shutdown works.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
