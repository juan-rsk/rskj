FROM openjdk:8-jre-slim-buster

RUN useradd -m rsk

USER rsk

COPY --chown=rsk:rsk ./artifacts /artifacts

WORKDIR /home/rsk

CMD ["/bin/sh", "-c", "java -cp /artifacts/rskj-core-*-all.jar co.rsk.Start"]

