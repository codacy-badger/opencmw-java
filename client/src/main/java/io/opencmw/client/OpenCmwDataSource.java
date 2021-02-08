package io.opencmw.client;

import io.opencmw.OpenCmwProtocol;
import io.opencmw.serialiser.IoSerialiser;
import io.opencmw.serialiser.spi.BinarySerialiser;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static io.opencmw.OpenCmwProtocol.Command.*;
import static io.opencmw.OpenCmwProtocol.EMPTY_FRAME;
import static io.opencmw.OpenCmwProtocol.MdpSubProtocol.PROT_CLIENT;

/**
 * Client implementation for the OpenCMW protocol.
 */
public class OpenCmwDataSource extends DataSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenCmwDataSource.class);
    public static final Factory FACTORY = new Factory() {
        @Override
        public boolean matches(final URI endpoint) {
            return endpoint.getScheme().equals("mdp") || endpoint.getScheme().equals("mds");
        }

        @Override
        public Class<? extends IoSerialiser> getMatchingSerialiserType(final URI endpoint) {
            return BinarySerialiser.class;
        }

        @Override
        public DataSource newInstance(final ZContext context, final URI endpoint, final Duration timeout, final String clientId) {
            return new OpenCmwDataSource(context, endpoint, timeout, clientId);
        }
    };
    private final ZContext context;
    private final Duration timeout;
    private final String clientId;
    private final URI endpoint;
    private final Socket socket;
    private final Socket subSocket = null;
    private final boolean noUdp = true; // use tcp based subscription. todo: default to false after implementing sub
    private final Map<String, URI> subscriptions = new HashMap<>();
    private final Map<String, URI> requests = new HashMap<>();

    /**
     * @param context zeroMQ context used for internal as well as external communication
     * @param endpoint The endpoint to connect to. Only the server part is used and everything else is discarded.
     * @param timeout Timeout to determine after what time a reaction from the server is expected. Futures will time out
     *                if there is no reaction from the server during this timespan.
     * @param clientId Identification string sent to the OpenCMW server. Should be unique per client and is used by the
     *                 Server to identify clients.
     */
    public OpenCmwDataSource(final ZContext context, final URI endpoint, final Duration timeout, final String clientId) {
        super(endpoint);
        this.endpoint = endpoint; // todo: Strip unneeded parts?
        this.context = context;
        this.timeout = timeout;
        this.clientId = clientId;
        this.socket = context.createSocket(SocketType.DEALER);
        socket.setHWM(0);
        socket.setIdentity(clientId.getBytes(StandardCharsets.UTF_8));
        socket.connect("tcp://" + this.endpoint.getAuthority());
        //this.subSocket = context.createSocket(SocketType.SUB);
        //subSocket.connect("udp://" + this.endpoint.getAuthority());
    }

    @Override
    public Socket getSocket() {
        return socket; // todo: also return sub socket?
    }

    @Override
    protected Factory getFactory() {
        return FACTORY;
    }

    @Override
    public ZMsg getMessage() {
        final OpenCmwProtocol.MdpMessage msg = OpenCmwProtocol.MdpMessage.receive(socket);
        switch (msg.protocol) {
            case PROT_CLIENT:
                return handleRequest(msg);
            case PROT_CLIENT_HTTP:
            case PROT_WORKER:
            case UNKNOWN:
            default:
                LOGGER.atDebug().addArgument(msg).log("Ignoring unexpected message: {}");
                return new ZMsg(); // ignore unknown request
        }
    }

    private ZMsg handleRequest(final OpenCmwProtocol.MdpMessage msg) {
        switch (msg.command) {
            case PARTIAL:
            case FINAL:
            case W_NOTIFY:
                if (msg.clientRequestID != null && msg.clientRequestID.length > 0) {
                    return createInternalMsg(msg.clientRequestID, msg.topic, new ZFrame(msg.data), msg.errors);
                }
                final String reqId = subscriptions.entrySet().stream()
                        .filter(e -> StringUtils.stripStart(e.getValue().getPath(),"/").equals(msg.topic.getPath()) &&
                                msg.topic.getQuery().startsWith(e.getValue().getQuery()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElseThrow();
                return createInternalMsg(reqId.getBytes(), msg.topic, new ZFrame(msg.data), msg.errors);
            case W_HEARTBEAT:
            case READY:
            case DISCONNECT:
            case UNSUBSCRIBE:
            case SUBSCRIBE:
            case GET_REQUEST:
            case SET_REQUEST:
            case UNKNOWN:
            default:
                LOGGER.atDebug().addArgument(msg).log("Ignoring unexpected message: {}");
                return new ZMsg(); // ignore unknown request
        }
    }

    private ZMsg createInternalMsg(final byte[] reqId, final URI endpoint, final ZFrame body, final String exception) {
        final ZMsg result = new ZMsg();
        result.add(reqId);
        result.add(endpoint.toString());
        result.add(new byte[0]);
        result.add(body == null ? new ZFrame(new byte[0]) : body);
        result.add(exception == null ? new ZFrame(new byte[0]) : new ZFrame(exception));
        return result;
    }

    @Override
    public long housekeeping() {
        return System.currentTimeMillis() + 1000;
    }

    @Override
    public void subscribe(final String reqId, final URI endpoint, final byte[] rbacToken) {
        subscriptions.put(reqId, endpoint);
        final byte[] serviceId = endpoint.getPath().substring(1).getBytes(StandardCharsets.UTF_8);
        if (noUdp) {
            // only tcp fallback?
            final boolean sent = new OpenCmwProtocol.MdpMessage(null,
                    PROT_CLIENT,
                    SUBSCRIBE,
                    serviceId,
                    reqId.getBytes(StandardCharsets.UTF_8),
                    endpoint,
                    EMPTY_FRAME,
                    "",
                    rbacToken).send(socket);
            if (!sent) {
                LOGGER.atError().addArgument(reqId).addArgument(endpoint).log("subscription error (reqId: {}) for endpoint: {}");
            }
        } else {
            subSocket.subscribe(serviceId);
        }
    }

    @Override
    public void unsubscribe(final String reqId) {
        final URI subscriptionEndpoint = subscriptions.get(reqId);
        final byte[] serviceId = subscriptionEndpoint.getPath().substring(1).getBytes(StandardCharsets.UTF_8);
        if (noUdp) {
            final boolean sent = new OpenCmwProtocol.MdpMessage(clientId.getBytes(StandardCharsets.UTF_8),
                    PROT_CLIENT,
                    UNSUBSCRIBE,
                    serviceId,
                    reqId.getBytes(StandardCharsets.UTF_8),
                    endpoint,
                    EMPTY_FRAME,
                    "",
                    null).send(socket);
            if (!sent) {
                LOGGER.atError().addArgument(reqId).addArgument(endpoint).log("unsubscribe error (reqId: {}) for endpoint: {}");
            }
        } else {
            subSocket.unsubscribe(serviceId);
        }
    }

    @Override
    public void get(final String requestId, final URI endpoint, final byte[] filters, final byte[] data, final byte[] rbacToken) {
        // todo: filters which are not in endpoint
        final byte[] serviceId = endpoint.getPath().substring(1).getBytes(StandardCharsets.UTF_8);
        final boolean sent = new OpenCmwProtocol.MdpMessage(null,
                PROT_CLIENT,
                GET_REQUEST,
                serviceId,
                requestId.getBytes(StandardCharsets.UTF_8),
                endpoint,
                EMPTY_FRAME,
                "",
                rbacToken).send(socket);
        if (!sent) {
            LOGGER.atError().addArgument(requestId).addArgument(endpoint).log("get error (reqId: {}) for endpoint: {}");
        }
    }

    @Override
    public void set(final String requestId, final URI endpoint, final byte[] filters, final byte[] data, final byte[] rbacToken) {
        // todo: filters which are not in endpoint
        final byte[] serviceId = endpoint.getPath().substring(1).getBytes(StandardCharsets.UTF_8);
        final boolean sent = new OpenCmwProtocol.MdpMessage(null,
                PROT_CLIENT,
                SET_REQUEST,
                serviceId,
                requestId.getBytes(StandardCharsets.UTF_8),
                endpoint,
                data,
                "",
                rbacToken).send(socket);
        if (!sent) {
            LOGGER.atError().addArgument(requestId).addArgument(endpoint).log("set error (reqId: {}) for endpoint: {}");
        }
    }
}
