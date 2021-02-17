package io.opencmw.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static io.opencmw.OpenCmwProtocol.Command.GET_REQUEST;
import static io.opencmw.OpenCmwProtocol.Command.SET_REQUEST;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opencmw.MimeType;
import io.opencmw.domain.BinaryData;
import io.opencmw.domain.NoData;
import io.opencmw.rbac.BasicRbacRole;

import zmq.util.Utils;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ClipboardWorkerTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardWorkerTests.class);
    private static final String CLIPBOARD_SERVICE_NAME = "clipboard";
    private MajordomoBroker broker;
    private String externalBrokerAddress;
    private String externalBrokerSubscriptionAddress;

    @BeforeAll
    void init() throws IOException {
        broker = new MajordomoBroker("TestMdpBroker", "", BasicRbacRole.values());
        // broker.setDaemon(true); // use this if running in another app that
        // controls threads Can be called multiple times with different endpoints
        externalBrokerAddress = broker.bind("mdp://localhost:" + Utils.findOpenPort());
        externalBrokerSubscriptionAddress = broker.bind("mds://localhost:" + Utils.findOpenPort());
        final ClipboardWorker clipboard = new ClipboardWorker(CLIPBOARD_SERVICE_NAME, broker.getContext(), null);
        assertEquals(CLIPBOARD_SERVICE_NAME, clipboard.getServiceName());

        broker.start();
        clipboard.start();

        // wait until all sockets and services are initialised
        LOGGER.atDebug().addArgument(externalBrokerAddress).log("ROUTER socket bound to: {}");
        LOGGER.atDebug().addArgument(externalBrokerSubscriptionAddress).log("PUB socket bound to: {}");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
    }

    @AfterAll
    void finish() {
        broker.stopBroker();
    }

    @Test
    void testSubmitExternal() {
        final BinaryData emptyDomainObject = new BinaryData();
        final BinaryData domainObject = new BinaryData("/testData.bin", MimeType.BINARY, new byte[] { 1, 2, 3, 4 });
        MajordomoTestClientSync client = new MajordomoTestClientSync(externalBrokerAddress, "customClientName");

        MajordomoTestClientSubscription<BinaryData> subClientRoot = new MajordomoTestClientSubscription<>(externalBrokerSubscriptionAddress, "subClientRoot", BinaryData.class);
        MajordomoTestClientSubscription<BinaryData> subClientMatching = new MajordomoTestClientSubscription<>(externalBrokerSubscriptionAddress, "subClientMatching", BinaryData.class);
        MajordomoTestClientSubscription<BinaryData> subClientNotMatching = new MajordomoTestClientSubscription<>(externalBrokerSubscriptionAddress, "subClientNotMatching", BinaryData.class);
        subClientRoot.subscribe(CLIPBOARD_SERVICE_NAME);
        subClientMatching.subscribe(CLIPBOARD_SERVICE_NAME + domainObject.resourceName);
        subClientNotMatching.subscribe(CLIPBOARD_SERVICE_NAME + "/differentData.bin");

        // wait to let subscription initialise
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));

        BinaryData replyMsg = client.send(SET_REQUEST, CLIPBOARD_SERVICE_NAME, domainObject, BinaryData.class);
        assertEquals(emptyDomainObject, replyMsg); // check that a default object has been returned

        replyMsg = client.send(GET_REQUEST, CLIPBOARD_SERVICE_NAME + domainObject.resourceName, new NoData(), BinaryData.class);
        assertEquals(domainObject, replyMsg); // check that a return objects are identical

        // send another object to the clipboard
        domainObject.resourceName = "/otherData.bin";
        client.send(SET_REQUEST, CLIPBOARD_SERVICE_NAME, domainObject, BinaryData.class);
        replyMsg = client.send(GET_REQUEST, CLIPBOARD_SERVICE_NAME + domainObject.resourceName, new NoData(), BinaryData.class);
        assertEquals(domainObject, replyMsg); // check that a return objects are identical

        // check subscription result
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
        System.err.println("messages = " + subClientRoot.mdpMessages.stream().map(s -> s.topic.toString()).collect(Collectors.joining(", ")));
        assertEquals(3, subClientRoot.mdpMessages.size(), "raw message count"); // N.B. '3' because we got notified twice on the client side because both 'clipboard' and 'clipboard/testData.bin'
        assertEquals(3, subClientRoot.domainMessages.size(), "domain message count");
        assertEquals(1, subClientMatching.mdpMessages.size(), "matching message count");
        assertEquals(1, subClientMatching.domainMessages.size(), "matching message count");
        assertEquals(0, subClientNotMatching.mdpMessages.size(), "non-matching message count");
        assertEquals(0, subClientNotMatching.domainMessages.size(), "non-matching message count");

        subClientRoot.stopClient();
    }
}
