package ru.raif.sdp;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

public class StateUpdateSupplier implements ProcessorSupplier<String, GenericRecord, String, GenericRecord> {
    private final StateUpdater stateUpdater;

    public StateUpdateSupplier(String storeName) {
        this.stateUpdater = new StateUpdater(storeName);
    }

    @Override
    public Processor<String, GenericRecord, String, GenericRecord> get() {
        return stateUpdater;
    }

    private static class StateUpdater implements Processor<String, GenericRecord, String, GenericRecord> {
        private final String storeName;
        private KeyValueStore<String, GenericRecord> stateStore;

        public StateUpdater(String storeName) {
            this.storeName = storeName;
        }

        @Override
        public void init(
            ProcessorContext<String, GenericRecord> context) {
            this.stateStore = context.getStateStore(storeName);
        }

        @Override
        public void process(Record<String, GenericRecord> record) {
            stateStore.put(record.key(), record.value());
        }

        @Override
        public void close() {
        }
    }
}
