package vn.dataplatform.cdc.sinks.bigquery;

import io.debezium.DebeziumException;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import io.debezium.serde.DebeziumSerdes;
import io.debezium.server.BaseChangeConsumer;
import io.debezium.util.Clock;
import io.debezium.util.Strings;
import io.debezium.util.Threads;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.dataplatform.cdc.sinks.bigquery.batchsizewait.InterfaceBatchSizeWait;


/**
 * @author tuan.nguyen3
 */
@Slf4j
public abstract class AbstractChangeConsumer extends BaseChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>>{
    protected static final Duration LOG_INTERVAL = Duration.ofMinutes(15);
    protected static final ConcurrentHashMap<String, Object> uploadLock = new ConcurrentHashMap<>();
    protected static final Serde<JsonNode> valSerde = DebeziumSerdes.payloadJson(JsonNode.class);
    protected static final Serde<JsonNode> keySerde = DebeziumSerdes.payloadJson(JsonNode.class);
    protected static final ObjectMapper mapper = new ObjectMapper();
    static Deserializer<JsonNode> keyDeserializer;
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    protected final Clock clock = Clock.system();
    protected Deserializer<JsonNode> valDeserializer;
    protected long consumerStart = clock.currentTimeInMillis();
    protected long numConsumedEvents = 0;
    protected Threads.Timer logTimer = Threads.timer(clock, LOG_INTERVAL);
    @ConfigProperty(name = "debezium.format.value", defaultValue = "json")
    String valueFormat;
    @ConfigProperty(name = "debezium.format.key", defaultValue = "json")
    String keyFormat;
    @ConfigProperty(name = "debezium.sink.batch.batch-size-wait", defaultValue = "NoBatchSizeWait")
    String batchSizeWaitName;
    @Inject
    @Any
    Instance<InterfaceBatchSizeWait> batchSizeWaitInstances;
    InterfaceBatchSizeWait batchSizeWait;

    public void initizalize() throws InterruptedException {
        // configure and set
        valSerde.configure(Collections.emptyMap(), false);
        valDeserializer = valSerde.deserializer();
        // configure and set
        keySerde.configure(Collections.emptyMap(), true);
        keyDeserializer = keySerde.deserializer();

        if (!valueFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
            throw new InterruptedException("debezium.format.value={" + valueFormat + "} not supported! Supported (debezium.format.value=*) formats are {json,}!");
        }

        if (!keyFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
            throw new InterruptedException("debezium.format.key={" + valueFormat + "} not supported! Supported (debezium.format.key=*) formats are {json,}!");
        }

        batchSizeWait = BatchUtil.selectInstance(batchSizeWaitInstances, batchSizeWaitName);
        LOGGER.info("Using {} to optimize batch size", batchSizeWait.getClass().getSimpleName());
        batchSizeWait.initizalize();
    }

    @Override
    public void handleBatch(List<ChangeEvent<Object, Object>> records, DebeziumEngine.RecordCommitter<ChangeEvent<Object, Object>> committer)
        throws InterruptedException {
        LOGGER.trace("Received {} events", records.size());

        Instant start = Instant.now();
        Map<String, List<DebeziumBigqueryEvent>> events = records.stream()
            .map((ChangeEvent<Object, Object> e)
                -> {
                try {
                    return new DebeziumBigqueryEvent(e.destination(),
                        valDeserializer.deserialize(e.destination(), getBytes(e.value())),
                        e.key() == null ? null : keyDeserializer.deserialize(e.destination(), getBytes(e.key())),
                        mapper.readTree(getBytes(e.value())).get("schema"),
                        e.key() == null ? null : mapper.readTree(getBytes(e.key())).get("schema")
                    );
                } catch (IOException ex) {
                    throw new DebeziumException(ex);
                }
            })
            .collect(Collectors.groupingBy(DebeziumBigqueryEvent::destination));
        long numUploadedEvents = 0;
        for (Map.Entry<String, List<DebeziumBigqueryEvent>> destinationEvents : events.entrySet()) {
            // group list of events by their schema, if in the batch we have schema change events grouped by their schema
            // so with this uniform schema is guaranteed for each batch
//            if(destinationEvents.getValue() != null) {
//                destinationEvents.getValue().forEach(e -> LOGGER.info("Event '{}'", e.valueSchema));
//                LOGGER.info("Destination {} got {} records", destinationEvents.getKey(), destinationEvents.getValue().size());
//                return ;
//            }
            Map<JsonNode, List<DebeziumBigqueryEvent>> eventsGroupedBySchema =
                destinationEvents.getValue().stream()
                    .collect(Collectors.groupingBy(DebeziumBigqueryEvent::valueSchema));
            LOGGER.debug("Destination {} got {} records with {} different schema!!", destinationEvents.getKey(),
                destinationEvents.getValue().size(),
                eventsGroupedBySchema.keySet().size());

            for (List<DebeziumBigqueryEvent> schemaEvents : eventsGroupedBySchema.values()) {
                numUploadedEvents += this.uploadDestination(destinationEvents.getKey(), schemaEvents);
            }
        }
        // workaround! somehow offset is not saved to file unless we call committer.markProcessed
        // even it's should be saved to file periodically
        for (ChangeEvent<Object, Object> record : records) {
            LOGGER.trace("Processed event '{}'", record);
            committer.markProcessed(record);
        }
        committer.markBatchFinished();
        this.logConsumerProgress(numUploadedEvents);
        LOGGER.debug("Received:{} Processed:{} events", records.size(), numUploadedEvents);

        batchSizeWait.waitMs(numUploadedEvents, (int) Duration.between(start, Instant.now()).toMillis());

    }

    protected void logConsumerProgress(long numUploadedEvents) {
        numConsumedEvents += numUploadedEvents;
        if (logTimer.expired()) {
            LOGGER.info("Consumed {} records after {}", numConsumedEvents, Strings.duration(clock.currentTimeInMillis() - consumerStart));
            numConsumedEvents = 0;
            consumerStart = clock.currentTimeInMillis();
            logTimer = Threads.timer(clock, LOG_INTERVAL);
        }
    }

    public abstract long uploadDestination(String destination, List<DebeziumBigqueryEvent> data);
}
