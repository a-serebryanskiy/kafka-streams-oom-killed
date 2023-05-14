# An example app for Kafka Summit 2023 lightning talk

This repository serves as a demonstration how to treat OOMKilled error
for a stateful Kafka Streams applications running in Kubernetes

## How to generate data

I used kafka connect datagen connector ([link to docs](https://github.com/confluentinc/kafka-connect-datagen)). 

To generate load I used config in file [datagen_connector_config.json](datagen_connector_config.json) and 
schema in [test_topic.avsc](test_topic.avsc).

## How to fix memory usage

1. Uncomment KafkaStreams configuration in [Application.java:43](src/main/java/ru/raif/sdp/Application.java#L43)
2. Uncomment heap limits in [deployment.yaml:43](helm/templates/deployment.yaml#L43)

## Metrics I used for keynote grafana dashboards

| Metric                               | PromQL                                                                                                                                                                                                                                                                                     |
|--------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JVM memory used                      | ```sum by (pod) (jvm_memory_used_bytes{pod=”your-pod-name"})```                                                                                                                                                                                                                            |
| Container memory usage               | ```container_memory_working_set_bytes{pod="your-pod-name",container="app"}```                                                                                                                                                                                                              |
| Container memory limit               | ```container_spec_memory_limit_bytes{pod="your-pod-name",container="app"}```                                                                                                                                                                                                               |
| RocksDB memory usage + os page cache | ```kafka_stream_state_size_all_mem_tables{pod=~"your-pod-name"} + kafka_stream_state_block_cache_usage{pod=~"your-pod-name"} + kafka_stream_state_estimate_table_readers_mem{pod=~"your-pod-name"} + on () container_memory_cache{pod="your-pod-name”, container="your-container-name"}``` |

## How to launch locally

1. Create a local .properties file with kafka streams properties. For example:

```properties
application.id: kafka-streams-oom-killed
state.dir: /tmp/states
bootstrap.servers: localhost:9092
schema.registry.url: http://localhost:8081
```

2. Add env var CONFIG_FILE_PATH with a path to the properties file.

## How to deploy to k8s

To deploy to k8s you'll need a running k8s cluster and docker, helm installed on your local machine.

1. build shadow jar
```bash
./gradlew shadowJar 
```
2. build Docker image
```bash
docker build -t your-image-tag:0.0.1 .
```
3. push Docker image to your preferred registry
```bash
docker push your-image-tag:0.0.1
```
4. change image in [deployment.yaml](helm/templates/deployment.yaml#L29)
5. add kafka streams configuration to [helm/templates/configmap.yaml](helm/templates/configmap.yaml)
6. execute helm update --install
```bash
helm upgrade --install --atomic --namespace your-namespace your-release-name helm/ 
```