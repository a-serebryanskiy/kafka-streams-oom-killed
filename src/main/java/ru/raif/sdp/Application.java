package ru.raif.sdp;

import com.sun.net.httpserver.HttpServer;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.confluent.kafka.streams.serdes.avro.GenericAvroSerde;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {

  public static final String STATE_STORE_NAME = "my-state-store";

  private static final String INPUT_TOPIC = "your-input-topic-name";
  private static final String CONFIG_FILE_PATH_ENV = "CONFIG_FILE_PATH";
  private static final Logger logger = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) throws IOException {
    var kafkaStreamsProperties = readPropertiesFromConfigFile();
//    kafkaStreamsProperties.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, BoundedMemoryRocksDBConfig.class);
    logger.info(kafkaStreamsProperties.toString());
    var schemaRegistryUrl = kafkaStreamsProperties.getProperty(
        KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG);
    var schemaRegistryClient = new CachedSchemaRegistryClient(schemaRegistryUrl, 16);
    var serDeProps = Map.of(
        KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl
    );

    var topology = buildTopology(schemaRegistryClient, serDeProps);

    var kafkaStreams = new KafkaStreams(topology, kafkaStreamsProperties);

    var registry = createMetricsRegistry(kafkaStreams);
    var httpServer = exposeRegistryViaHttp(registry);

    var latch = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread("shutdown-hook") {
      @Override
      public void run() {
        kafkaStreams.close();
        latch.countDown();
      }
    });

    kafkaStreams.setUncaughtExceptionHandler((ex) -> {
      ex.printStackTrace();
      latch.countDown();
      return StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
    });

    try {
      kafkaStreams.start();
      latch.await();
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    } finally {
      httpServer.stop(0);
    }

    System.exit(0);
  }

  private static Properties readPropertiesFromConfigFile() throws IOException {
    Properties kafkaStreamsProperties = new Properties();
    String configFilePath = System.getenv(CONFIG_FILE_PATH_ENV);
    try (var fileReader = new FileReader(configFilePath)) {
      kafkaStreamsProperties.load(fileReader);
    }
    return kafkaStreamsProperties;
  }

  private static PrometheusMeterRegistry createMetricsRegistry(KafkaStreams kafkaStreams) {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    new KafkaStreamsMetrics(kafkaStreams).bindTo(registry);
    new JvmMemoryMetrics().bindTo(registry);
    return registry;
  }

  public static Topology buildTopology(SchemaRegistryClient client,
      Map<String, String> serDeConfig) {
    var builder = new StreamsBuilder();
    var avroSerde = new GenericAvroSerde(client);
    avroSerde.configure(serDeConfig, false);

    var stream = builder.stream(INPUT_TOPIC,
        Consumed.with(Serdes.String(), avroSerde));

    var stateStoreSupplier =
        Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE_NAME),
                Serdes.String(), avroSerde
            )
            .withCachingDisabled()
            .withLoggingEnabled(Collections.emptyMap());
    builder.addStateStore(stateStoreSupplier);

    var persistedStream = stream.transformValues(SimpleTransformer::new, STATE_STORE_NAME);

    var counter = new AtomicLong(0);
    persistedStream.foreach((key, val) -> {
      if (counter.incrementAndGet() % 10_000 == 0) {
        logger.info("Processed {} records", counter.get());
      }
    });

    return builder.build();
  }

  private static HttpServer exposeRegistryViaHttp(PrometheusMeterRegistry registry) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
      server.createContext("/prometheus", httpExchange -> {
        String response = registry.scrape();
        httpExchange.sendResponseHeaders(200, response.getBytes().length);
        try (OutputStream os = httpExchange.getResponseBody()) {
          os.write(response.getBytes());
        }
      });

      new Thread(server::start).start();
      return server;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
