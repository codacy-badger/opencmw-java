package io.opencmw.client;

import io.opencmw.EventStore;
import io.opencmw.MimeType;
import io.opencmw.filter.EvtTypeFilter;
import io.opencmw.filter.TimingCtx;
import io.opencmw.rbac.BasicRbacRole;
import io.opencmw.server.MajordomoBroker;
import io.opencmw.server.MajordomoWorker;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.Utils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class OpenCmwDataSourceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenCmwDataSourceTest.class);

    @Test
    void testSubscription() throws IOException, URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        MajordomoBroker broker = new MajordomoBroker("TestBroker", "", BasicRbacRole.values());
        final int brokerPort = Utils.findOpenPort();
        final String brokerAddress = broker.bind("mdp://*:" + brokerPort);
        LOGGER.atDebug().addArgument(brokerAddress).log("started broker on {}");

        final TestObject referenceObject = new TestObject("asdf", 42);

        final MajordomoWorker<TestContext, TestObject, TestObject> worker = new MajordomoWorker<>(
                broker.getContext(),
                "/testWorker",
                TestContext.class,
                TestObject.class,
                TestObject.class);
        worker.start();
        broker.start();

        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());

        DataSource.register(OpenCmwDataSource.FACTORY);
        final EventStore eventStore = EventStore.getFactory().setFilterConfig(TimingCtx.class, EvtTypeFilter.class).build();
        final DataSourcePublisher dataSourcePublisher = new DataSourcePublisher(null, eventStore);

        final Map<String, TestObject> updates = new ConcurrentHashMap<>();

        eventStore.register((event, seq, last) -> {
             LOGGER.atDebug().addArgument(event).log("received event: {}");
             updates.put(event.getFilter(TimingCtx.class).selector, event.payload.get(TestObject.class));
        });

        eventStore.start();
        new Thread(dataSourcePublisher).start();
        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        final URI requestURI = new URI("mdp", "localhost:" + brokerPort, "/testWorker", "ctx=FAIR.SELECTOR.C=2", null);
        LOGGER.atDebug().addArgument(requestURI).log("subscribing to endpoint: {}");
        dataSourcePublisher.subscribe(requestURI, TestObject.class);

        for (int i = 1; i < 5; i++) {
            worker.notify(new TestContext("FAIR.SELECTOR.C=2:P=" + i), new TestObject("update", i));
        }

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> updates.size() >= 4);
        for (int i = 1; i < 5; i++) {
            assertEquals(new TestObject("update", i), updates.get("FAIR.SELECTOR.C=2:P=" + i));
        }

        eventStore.stop();
        worker.stopWorker();
        broker.stopBroker();
    }

    @Test
    void testGetRequest() throws IOException, URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        MajordomoBroker broker = new MajordomoBroker("TestBroker", "", BasicRbacRole.values());
        final int brokerPort = Utils.findOpenPort();
        final String brokerAddress = broker.bind("mdp://*:" + brokerPort);
        LOGGER.atDebug().addArgument(brokerAddress).log("started broker on {}");

        final TestObject referenceObject = new TestObject("asdf", 42);

        final MajordomoWorker<TestContext, TestObject, TestObject> worker = new MajordomoWorker<>(
                broker.getContext(),
                "testWorker",
                TestContext.class,
                TestObject.class,
                TestObject.class);
        worker.setHandler((ctx, requestCtx, request, replyCtx, reply) -> {
            assertEquals("FAIR.SELECTOR.C=3", requestCtx.ctx);
            replyCtx.ctx = "FAIR.SELECTOR.C=3:P=5";
            reply.set(referenceObject);
            LOGGER.atDebug().addArgument(requestCtx).addArgument(request).addArgument(replyCtx).addArgument(reply).log("got get request: {}, {} -> {}, {}");
        });
        worker.start();
        broker.start();

        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());

        DataSource.register(OpenCmwDataSource.FACTORY);
        final EventStore eventStore = EventStore.getFactory().setFilterConfig(TimingCtx.class, EvtTypeFilter.class).build();
        final DataSourcePublisher dataSourcePublisher = new DataSourcePublisher(null, eventStore);
        eventStore.start();
        new Thread(dataSourcePublisher).start();
        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        final URI requestURI = new URI("mdp", "localhost:" + brokerPort, "/testWorker", "ctx=FAIR.SELECTOR.C=3&mimeType=application/octet-stream", null);
        LOGGER.atDebug().addArgument(requestURI).log("requesting GET from endpoint: {}");
        final Future<TestObject> future = dataSourcePublisher.get(requestURI , TestObject.class);
        final TestObject result = future.get(1000, TimeUnit.MILLISECONDS);
        assertEquals(referenceObject, result);

        eventStore.stop();
        worker.stopWorker();
        broker.stopBroker();
    }

    @Test
    void testSetRequest() throws IOException, URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
        MajordomoBroker broker = new MajordomoBroker("TestBroker", "", BasicRbacRole.values());
        final int brokerPort = Utils.findOpenPort();
        final String brokerAddress = broker.bind("mdp://*:" + brokerPort);
        LOGGER.atDebug().addArgument(brokerAddress).log("started broker on {}");

        final TestObject toSet = new TestObject("newValue", 666);

        final MajordomoWorker<TestContext, TestObject, TestObject> worker = new MajordomoWorker<>(
                broker.getContext(),
                "testWorker",
                TestContext.class,
                TestObject.class,
                TestObject.class);
        worker.setHandler((ctx, requestCtx, request, replyCtx, reply) -> {
            assertEquals("FAIR.SELECTOR.C=3", requestCtx.ctx);
            replyCtx.ctx = "FAIR.SELECTOR.C=3:P=5";
            reply.set(request);
            LOGGER.atDebug().addArgument(requestCtx).addArgument(request).addArgument(replyCtx).addArgument(reply).log("got get request: {}, {} -> {}, {}");
        });
        worker.start();
        broker.start();

        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());

        DataSource.register(OpenCmwDataSource.FACTORY);
        final EventStore eventStore = EventStore.getFactory().setFilterConfig(TimingCtx.class, EvtTypeFilter.class).build();
        final DataSourcePublisher dataSourcePublisher = new DataSourcePublisher(null, eventStore);
        eventStore.start();
        new Thread(dataSourcePublisher).start();
        LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        final URI requestURI = new URI("mdp", "localhost:" + brokerPort, "/testWorker", "ctx=FAIR.SELECTOR.C=3", null);
        LOGGER.atDebug().addArgument(requestURI).log("requesting GET from endpoint: {}");
        final Future<TestObject> future = dataSourcePublisher.set(requestURI , TestObject.class, toSet);
        final TestObject result = future.get(1000, TimeUnit.MILLISECONDS);
        assertEquals(toSet, result);

        eventStore.stop();
        worker.stopWorker();
        broker.stopBroker();
    }

    public static class TestObject {
        private String foo;
        private double bar;

        public TestObject(final String foo, final double bar) {
            this.foo = foo;
            this.bar = bar;
        }

        public TestObject() {
            this.foo = "";
            this.bar = Double.NaN;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o)
                return true;
            if (!(o instanceof TestObject))
                return false;
            final TestObject that = (TestObject) o;
            return bar == that.bar && Objects.equals(foo, that.foo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(foo, bar);
        }

        @Override
        public String
        toString() {
            return "{foo='" + foo + '\'' + ", bar=" + bar + '}';
        }

        public void set(final TestObject other) {
            this.foo = other.foo;
            this.bar = other.bar;
        }
    }

    public static class TestContext {
        public String ctx;
        public MimeType contentType = MimeType.BINARY;

        public TestContext() {
            // do nothing, needed for reflexive instantiation
        }

        public TestContext(final String context) {
            ctx = context;
        }

        @Override
        public String toString() {
            return ctx;
        }
    }

}