FROM openjdk:8-jre-slim-buster
RUN useradd -m rsk
USER rsk
WORKDIR /home/rsk
COPY --chown=rsk:rsk /home/runner/work/rskj/rskj/rskj-artifacts/rskj-artifacts/rskj-core-*-all.jar rsk.jar
CMD ["java", "-cp", "rsk.jar", "co.rsk.Start"]

