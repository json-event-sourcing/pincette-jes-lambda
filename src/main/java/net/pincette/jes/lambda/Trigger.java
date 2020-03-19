package net.pincette.jes.lambda;

import static java.lang.System.exit;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.parse;
import static java.util.logging.Logger.getLogger;
import static net.pincette.jes.elastic.Logging.log;
import static net.pincette.jes.util.Configuration.loadDefault;
import static net.pincette.jes.util.Kafka.createReliableProducer;
import static net.pincette.jes.util.Kafka.fromConfig;
import static net.pincette.jes.util.Streams.start;
import static net.pincette.json.JsonUtil.string;
import static net.pincette.util.Util.tryToGetSilent;
import static software.amazon.awssdk.core.SdkBytes.fromUtf8String;
import static software.amazon.awssdk.services.lambda.LambdaAsyncClient.create;
import static software.amazon.awssdk.services.lambda.model.InvocationType.EVENT;
import static software.amazon.awssdk.services.lambda.model.InvokeRequest.builder;

import com.typesafe.config.Config;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.json.JsonObject;
import net.pincette.function.SideEffect;
import net.pincette.jes.util.JsonSerializer;
import net.pincette.jes.util.Streams;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import software.amazon.awssdk.services.lambda.LambdaAsyncClient;

/**
 * This program listens to configured Kafka topics for messages of the type <code>JsonObject</code>
 * and calls a configured array of lambdas with them. The messages are sent as events and the
 * lambdas are called asychronously.
 *
 * @author Werner Donn\u00e9
 */
public class Trigger {
  private static final String ENVIRONMENT = "environment";
  private static final String KAFKA = "kafka";
  private static final String LOG_LEVEL = "logLevel";
  private static final String LOG_TOPIC = "logTopic";
  private static final String VERSION = "1.0";

  private static void callLambda(
      final String arn,
      final JsonObject message,
      final LambdaAsyncClient client,
      final Logger logger) {
    client
        .invoke(
            builder()
                .functionName(arn)
                .invocationType(EVENT)
                .payload(fromUtf8String(string(message)))
                .build())
        .thenApply(
            response ->
                Optional.ofNullable(response.functionError())
                    .map(
                        error ->
                            SideEffect.<Boolean>run(
                                    () ->
                                        logger.log(
                                            SEVERE,
                                            "Calling lambda {0} returned error {1} with payload {2}",
                                            new Object[] {
                                              arn, error, response.payload().asUtf8String()
                                            }))
                                .andThenGet(() -> false))
                    .orElse(true))
        .exceptionally(
            e ->
                SideEffect.<Boolean>run(
                        () ->
                            logger.log(SEVERE, "Calling lambda " + arn + " returned exception", e))
                    .andThenGet(() -> false));
  }

  private static void connect(
      final String topic,
      final List<String> arns,
      final LambdaAsyncClient client,
      final StreamsBuilder builder,
      final Logger logger) {
    final KStream<String, JsonObject> stream = builder.stream(topic);

    stream.mapValues(
        v ->
            SideEffect.<Boolean>run(() -> arns.forEach(arn -> callLambda(arn, v, client, logger)))
                .andThenGet(() -> true));
  }

  static String env(final Config config) {
    return tryToGetSilent(() -> config.getString(ENVIRONMENT)).orElse("dev");
  }

  private static Level logLevel(final Config config) {
    return parse(tryToGetSilent(() -> config.getString(LOG_LEVEL)).orElse("INFO"));
  }

  public static void main(final String[] args) {
    final StreamsBuilder builder = new StreamsBuilder();
    final Config config = loadDefault();
    final Logger logger = getLogger("pincette-jes-lambda");
    final Config triggers = config.getConfig("triggers");

    logger.setLevel(logLevel(config));

    try (final LambdaAsyncClient client = create();
        final KafkaProducer<String, JsonObject> producer =
            createReliableProducer(
                fromConfig(config, KAFKA), new StringSerializer(), new JsonSerializer())) {
      log(logger, logger.getLevel(), VERSION, env(config), producer, config.getString(LOG_TOPIC));

      triggers
          .entrySet()
          .forEach(
              e ->
                  connect(e.getKey(), triggers.getStringList(e.getKey()), client, builder, logger));

      final Topology topology = builder.build();

      logger.log(INFO, "Topology:\n\n {0}", topology.describe());

      if (!start(topology, Streams.fromConfig(config, KAFKA))) {
        exit(1);
      }
    }
  }
}
