# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Bake model weights into the image so pods never reach the internet (HF_HUB_OFFLINE=1 at runtime).
FROM build AS models
ARG KL_CONFIG_FILE=examples/kl.yaml
COPY examples ./examples
ENV HF_HOME=/models
RUN java -jar target/knowledgelake-*-all.jar -c ${KL_CONFIG_FILE} fetch-models

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home kl && mkdir -p /cache /models && chown kl /cache
COPY --from=build /src/target/knowledgelake-*-all.jar /app/knowledgelake.jar
COPY --from=models /models /models
RUN printf '#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector $KL_JAVA_OPTS -jar /app/knowledgelake.jar "$@"\n' > /usr/local/bin/kl \
    && chmod +x /usr/local/bin/kl
USER kl
ENV HF_HOME=/models HF_HUB_OFFLINE=1 KL_JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["kl"]
CMD ["serve", "--port", "8080"]
