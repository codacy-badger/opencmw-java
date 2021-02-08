package io.opencmw.client;

import io.opencmw.Filter;
import io.opencmw.serialiser.IoSerialiser;

import java.net.URI;

public class DataSourceFilter implements Filter {
    public ReplyType eventType = ReplyType.UNKNOWN;
    public Class<? extends IoSerialiser> protocolType;
    public URI endpoint = null;
    public DataSourcePublisher.ThePromisedFuture<?> future;
    public String context;

    @Override
    public void clear() {
        eventType = ReplyType.UNKNOWN;
        protocolType = null; // NOPMD - have to clear the future because the events are reused
        endpoint = null; // NOPMD - have to clear the future because the events are reused
        future = null; // NOPMD - have to clear the future because the events are reused
        context = "";
    }

    @Override
    public void copyTo(final Filter other) {
        if (other instanceof DataSourceFilter) {
            final DataSourceFilter otherDSF = (DataSourceFilter) other;
            otherDSF.eventType = eventType;
            otherDSF.endpoint = endpoint;
            otherDSF.future = future;
            otherDSF.context = context;
            otherDSF.protocolType = protocolType;
        }
    }

    /**
     * internal enum to track different get/set/subscribe/... transactions
     */
    public enum ReplyType {
        SUBSCRIBE(0),
        GET(1),
        SET(2),
        UNSUBSCRIBE(3),
        UNKNOWN(-1);

        private final byte id;
        ReplyType(int id) {
            this.id = (byte) id;
        }

        public byte getID() {
            return id;
        }

        public static ReplyType valueOf(final int id) {
            for (ReplyType mode : ReplyType.values()) {
                if (mode.getID() == id) {
                    return mode;
                }
            }
            return UNKNOWN;
        }
    }
}
