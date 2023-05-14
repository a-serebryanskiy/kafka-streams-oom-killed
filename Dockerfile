FROM openjdk:11-slim
USER root
WORKDIR /app/

COPY ./build/libs/app.jar .

ENV LANG en_US.utf8

RUN apt-get update -y && \
    apt-get install -y locales && \
    apt-get install -y procps && \
    sed -i 's/^# *\(ru_RU.UTF-8\)/\1/' /etc/locale.gen && \
    sed -i 's/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen && \
    locale-gen && \
    locale -a && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/share/zoneinfo/Europe/Moscow /etc/localtime && \
    echo 'Europe/Moscow' > /etc/timezone && \
    groupadd --gid 2000 jvm_group && useradd --uid 2000 --gid jvm_group --shell /bin/bash jvm_user && \
    chown -R 2000:2000 /app

USER jvm_user
