package ru.raif.sdp;

import static ru.raif.sdp.Application.STATE_STORE_NAME;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;

public class SimpleTransformer implements
    ValueTransformerWithKey<String, GenericRecord, GenericRecord> {

  private KeyValueStore<String, GenericRecord> stateStore;

  @Override
  public void init(ProcessorContext context) {
    this.stateStore = context.getStateStore(STATE_STORE_NAME);
  }

  @Override
  public GenericRecord transform(String readOnlyKey, GenericRecord value) {
    stateStore.put(readOnlyKey, value);
    return value;
  }

  @Override
  public void close() {

  }
}
