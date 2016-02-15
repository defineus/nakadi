package de.zalando.aruha.nakadi.repository.kafka;

import com.google.common.collect.ImmutableMap;
import de.zalando.aruha.nakadi.NakadiException;
import de.zalando.aruha.nakadi.domain.Cursor;
import de.zalando.aruha.nakadi.domain.Topic;
import de.zalando.aruha.nakadi.domain.TopicPartition;
import de.zalando.aruha.nakadi.repository.EventConsumer;
import de.zalando.aruha.nakadi.repository.TopicCreationException;
import de.zalando.aruha.nakadi.repository.TopicRepository;
import de.zalando.aruha.nakadi.repository.zookeeper.ZooKeeperHolder;
import kafka.admin.AdminUtils;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.TopicAndPartition;
import kafka.javaapi.OffsetRequest;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.utils.ZkUtils;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static de.zalando.aruha.nakadi.repository.kafka.KafkaCursor.fromNakadiCursor;
import static de.zalando.aruha.nakadi.repository.kafka.KafkaCursor.toKafkaOffset;
import static de.zalando.aruha.nakadi.repository.kafka.KafkaCursor.toKafkaPartition;
import static de.zalando.aruha.nakadi.repository.kafka.KafkaCursor.toNakadiOffset;
import static de.zalando.aruha.nakadi.repository.kafka.KafkaCursor.toNakadiPartition;
import static kafka.api.OffsetRequest.EarliestTime;
import static kafka.api.OffsetRequest.LatestTime;

public class KafkaRepository implements TopicRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRepository.class);

    private final ZooKeeperHolder zkFactory;
    private final Producer<String, String> kafkaProducer;
    private final KafkaFactory kafkaFactory;
    private final KafkaRepositorySettings settings;

    public KafkaRepository(final ZooKeeperHolder zkFactory, final KafkaFactory kafkaFactory,
                           final KafkaRepositorySettings settings) {
        this.zkFactory = zkFactory;
        this.kafkaProducer = kafkaFactory.createProducer();
        this.kafkaFactory = kafkaFactory;
        this.settings = settings;
    }

    @Override
    public List<Topic> listTopics() throws NakadiException {
        try {
            return zkFactory.get()
                    .getChildren()
                    .forPath("/brokers/topics")
                    .stream()
                    .map(Topic::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new NakadiException("Failed to list topics", e);
        }
    }

    @Override
    public void createTopic(final String topic) throws TopicCreationException {
        createTopic(topic,
                settings.getDefaultTopicPartitionNum(),
                settings.getDefaultTopicReplicaFactor(),
                settings.getDefaultTopicRetentionMs(),
                settings.getDefaultTopicRotationMs());
    }

    @Override
    public void createTopic(final String topic, final int partitionsNum, final int replicaFactor,
                            final long retentionMs, final long rotationMs) throws TopicCreationException {
        ZkUtils zkUtils = null;
        try {
            final String connectionString = zkFactory.get().getZookeeperClient().getCurrentConnectionString();
            zkUtils = ZkUtils.apply(connectionString, settings.getZkSessionTimeoutMs(),
                    settings.getZkConnectionTimeoutMs(), false);

            final Properties topicConfig = new Properties();
            topicConfig.setProperty("retention.ms", Long.toString(retentionMs));
            topicConfig.setProperty("segment.ms", Long.toString(rotationMs));

            AdminUtils.createTopic(zkUtils, topic, partitionsNum, replicaFactor, topicConfig);
        } catch (Exception e) {
            throw new TopicCreationException("unable to create topic", e);
        }
        finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    @Override
    public boolean topicExists(final String topic) throws NakadiException {
        return listTopics()
                .stream()
                .map(Topic::getName)
                .anyMatch(t -> t.equals(topic));
    }

    @Override
    public boolean partitionExists(final String topic, final String partition) throws NakadiException {
        return kafkaFactory
                .getConsumer()
                .partitionsFor(topic)
                .stream()
                .anyMatch(pInfo -> toNakadiPartition(pInfo.partition()).equals(partition));
    }

    @Override
    public boolean areCursorsValid(final String topic, final List<Cursor> cursors) throws NakadiException {
        final List<TopicPartition> partitions = listPartitions(topic);
        return cursors
                .stream()
                .allMatch(cursor -> partitions
                        .stream()
                        .filter(tp -> tp.getPartitionId().equals(cursor.getPartition()))
                        .findFirst()
                        .map(pInfo -> {
                            final long newestOffset = toKafkaOffset(pInfo.getNewestAvailableOffset());
                            final long oldestOffset = toKafkaOffset(pInfo.getOldestAvailableOffset());
                            try {
                                final long offset = fromNakadiCursor(cursor).getOffset();
                                return offset >= oldestOffset && offset <= newestOffset;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        })
                        .orElse(false));
    }

    @Override
    public void postEvent(final String topicId, final String partitionId, final String payload) throws NakadiException {
        LOG.info("Posting {} {} {}", topicId, partitionId, payload);

        final ProducerRecord<String, String> record = new ProducerRecord<>(topicId, toKafkaPartition(partitionId),
                partitionId, payload);
        try {
            kafkaProducer.send(record).get(settings.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new NakadiException("Failed to send event", e);
        }
    }

    @Override
    public List<TopicPartition> listPartitions(final String topicId) throws NakadiException {

        final SimpleConsumer sc = kafkaFactory.getSimpleConsumer();
        try {
            final List<TopicAndPartition> partitions = kafkaFactory
                    .getConsumer()
                    .partitionsFor(topicId)
                    .stream()
                    .map(p -> new TopicAndPartition(p.topic(), p.partition()))
                    .collect(Collectors.toList());

            final Map<TopicAndPartition, PartitionOffsetRequestInfo> latestPartitionRequests = partitions
                    .stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            t -> new PartitionOffsetRequestInfo(LatestTime(), 1)));
            final Map<TopicAndPartition, PartitionOffsetRequestInfo> earliestPartitionRequests = partitions
                    .stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            t -> new PartitionOffsetRequestInfo(EarliestTime(), 1)));

            final OffsetResponse latestPartitionData = fetchPartitionData(sc, latestPartitionRequests);
            final OffsetResponse earliestPartitionData = fetchPartitionData(sc, earliestPartitionRequests);

            return partitions
                    .stream()
                    .map(r -> processTopicPartitionMetadata(r, latestPartitionData, earliestPartitionData))
                    .collect(Collectors.toList());
        }
        catch (Exception e) {
            throw new NakadiException("Error occurred when fetching partitions offsets", e);
        }
        finally {
            sc.close();
        }
    }

    @Override
    public TopicPartition getPartition(final String topicId, final String partition) throws NakadiException {
        final SimpleConsumer consumer = kafkaFactory.getSimpleConsumer();
        try {
            final TopicAndPartition topicAndPartition = new TopicAndPartition(topicId, Integer.parseInt(partition));

            final OffsetResponse latestPartitionData = fetchPartitionData(consumer, ImmutableMap.of(
                    topicAndPartition, new PartitionOffsetRequestInfo(LatestTime(), 1)));

            final OffsetResponse earliestPartitionData = fetchPartitionData(consumer, ImmutableMap.of(
                    topicAndPartition, new PartitionOffsetRequestInfo(EarliestTime(), 1)));

            return processTopicPartitionMetadata(topicAndPartition, latestPartitionData, earliestPartitionData);
        }
        catch (Exception e) {
            throw new NakadiException("Error occurred when fetching partition offsets", e);
        }
        finally {
            consumer.close();
        }
    }

    private TopicPartition processTopicPartitionMetadata(final TopicAndPartition partition,
                                                         final OffsetResponse latestPartitionData,
                                                         final OffsetResponse earliestPartitionData) {

        final TopicPartition tp = new TopicPartition(partition.topic(), toNakadiPartition(partition.partition()));
        final long latestOffset = latestPartitionData.offsets(partition.topic(), partition.partition())[0];
        final long earliestOffset = earliestPartitionData.offsets(partition.topic(), partition.partition())[0];

        tp.setNewestAvailableOffset(toNakadiOffset(latestOffset));
        tp.setOldestAvailableOffset(toNakadiOffset(earliestOffset));

        return tp;
    }

    private OffsetResponse fetchPartitionData(final SimpleConsumer sc,
                                              final Map<TopicAndPartition,
                                                      PartitionOffsetRequestInfo> partitionRequests) {
        final OffsetRequest request = new OffsetRequest(partitionRequests,
                kafka.api.OffsetRequest.CurrentVersion(), "offsetlookup_" + UUID.randomUUID());
        return sc.getOffsetsBefore(request);
    }

    @Override
    public EventConsumer createEventConsumer(final String topic, final Map<String, String> cursors) {
        return new NakadiKafkaConsumer(kafkaFactory, topic, cursors, settings.getKafkaPollTimeoutMs());
    }
}