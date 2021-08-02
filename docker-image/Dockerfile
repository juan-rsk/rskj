FROM openjdk:8-jre-slim-buster

RUN useradd -m rsk

WORKDIR /home/rsk

USER rsk
COPY --chown=rsk:rsk ./rskj-artifacts /artifacts
CMD ["/bin/sh", "-c", "java -cp /artifacts/rskj-core-*-all.jar co.rsk.Start"]

