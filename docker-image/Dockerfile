# tag name:	openjdk:8-jdk-slim-buster 
# retrieved:	Thu Sep  3 20:59:42 UTC 2020
FROM openjdk@sha256:335627a2118556b3f412287e08ac7febb8ecc46325dc6b3570db56f32d33938f

COPY ./ /code/rskj

RUN apt-get update -y && \
    apt-get install --no-install-recommends -y curl git gnupg && \
    rm -rf /var/lib/apt/lists/* && \
    apt-get autoremove -y && \
    apt-get clean

WORKDIR /code/rskj

RUN ./configure.sh && ./gradlew --no-daemon clean build -x test
CMD ["/bin/sh", "-c", "java -cp rskj-core/build/libs/rskj-core-*-all.jar co.rsk.Start"]
