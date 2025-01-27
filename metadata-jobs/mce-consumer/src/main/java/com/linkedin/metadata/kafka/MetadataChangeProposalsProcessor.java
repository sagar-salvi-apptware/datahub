package com.linkedin.metadata.kafka;

import static com.linkedin.metadata.Constants.MDC_ASPECT_NAME;
import static com.linkedin.metadata.Constants.MDC_CHANGE_TYPE;
import static com.linkedin.metadata.Constants.MDC_ENTITY_TYPE;
import static com.linkedin.metadata.Constants.MDC_ENTITY_URN;
import static com.linkedin.metadata.config.kafka.KafkaConfiguration.MCP_EVENT_CONSUMER_NAME;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.linkedin.common.urn.Urn;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.gms.factory.config.ConfigurationProvider;
import com.linkedin.gms.factory.entityclient.RestliEntityClientFactory;
import com.linkedin.metadata.EventUtils;
import com.linkedin.metadata.dao.throttle.ThrottleSensor;
import com.linkedin.metadata.kafka.config.MetadataChangeProposalProcessorCondition;
import com.linkedin.metadata.kafka.util.KafkaListenerUtil;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.Topics;
import io.datahubproject.metadata.context.OperationContext;
import java.util.Optional;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Import({RestliEntityClientFactory.class})
@Conditional(MetadataChangeProposalProcessorCondition.class)
@EnableKafka
@RequiredArgsConstructor
public class MetadataChangeProposalsProcessor {
  private static final String CONSUMER_GROUP_ID_VALUE =
      "${METADATA_CHANGE_PROPOSAL_KAFKA_CONSUMER_GROUP_ID:generic-mce-consumer-job-client}";

  private final OperationContext systemOperationContext;
  private final SystemEntityClient entityClient;
  private final Producer<String, IndexedRecord> kafkaProducer;

  @Qualifier("kafkaThrottle")
  private final ThrottleSensor kafkaThrottle;

  private final KafkaListenerEndpointRegistry registry;
  private final ConfigurationProvider provider;

  private final Histogram kafkaLagStats =
      MetricUtils.get().histogram(MetricRegistry.name(this.getClass(), "kafkaLag"));

  @Value(
      "${FAILED_METADATA_CHANGE_PROPOSAL_TOPIC_NAME:"
          + Topics.FAILED_METADATA_CHANGE_PROPOSAL
          + "}")
  private String fmcpTopicName;

  @Value(CONSUMER_GROUP_ID_VALUE)
  private String mceConsumerGroupId;

  @PostConstruct
  public void registerConsumerThrottle() {
    KafkaListenerUtil.registerThrottle(kafkaThrottle, provider, registry, mceConsumerGroupId);
  }

  @KafkaListener(
      id = CONSUMER_GROUP_ID_VALUE,
      topics = "${METADATA_CHANGE_PROPOSAL_TOPIC_NAME:" + Topics.METADATA_CHANGE_PROPOSAL + "}",
      containerFactory = MCP_EVENT_CONSUMER_NAME,
      autoStartup = "false")
  public void consume(final ConsumerRecord<String, GenericRecord> consumerRecord) {
    try (Timer.Context ignored = MetricUtils.timer(this.getClass(), "consume").time()) {
      kafkaLagStats.update(System.currentTimeMillis() - consumerRecord.timestamp());
      final GenericRecord record = consumerRecord.value();

      log.info(
          "Got MCP event key: {}, topic: {}, partition: {}, offset: {}, value size: {}, timestamp: {}",
          consumerRecord.key(),
          consumerRecord.topic(),
          consumerRecord.partition(),
          consumerRecord.offset(),
          consumerRecord.serializedValueSize(),
          consumerRecord.timestamp());

      if (log.isDebugEnabled()) {
        log.debug("Record {}", record);
      }

      MetadataChangeProposal event = new MetadataChangeProposal();
      try {
        event = EventUtils.avroToPegasusMCP(record);

        Urn entityUrn = event.getEntityUrn();
        String aspectName = event.hasAspectName() ? event.getAspectName() : null;
        String entityType = event.hasEntityType() ? event.getEntityType() : null;
        ChangeType changeType = event.hasChangeType() ? event.getChangeType() : null;
        MDC.put(MDC_ENTITY_URN, Optional.ofNullable(entityUrn).map(Urn::toString).orElse(""));
        MDC.put(MDC_ASPECT_NAME, aspectName);
        MDC.put(MDC_ENTITY_TYPE, entityType);
        MDC.put(
            MDC_CHANGE_TYPE, Optional.ofNullable(changeType).map(ChangeType::toString).orElse(""));

        if (log.isDebugEnabled()) {
          log.debug("MetadataChangeProposal {}", event);
        }
        String urn = entityClient.ingestProposal(systemOperationContext, event, false);
        log.info("Successfully processed MCP event urn: {}", urn);
      } catch (Throwable throwable) {
        log.error("MCP Processor Error", throwable);
        log.error("Message: {}", record);
        KafkaListenerUtil.sendFailedMCP(event, throwable, fmcpTopicName, kafkaProducer);
      }
    } finally {
      MDC.clear();
    }
  }
}
