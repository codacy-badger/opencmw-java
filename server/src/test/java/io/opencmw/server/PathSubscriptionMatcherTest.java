package io.opencmw.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

class PathSubscriptionMatcherTest {
    @Test
    void testMatcher() {
        final BiPredicate<URI, URI> matcher = new PathSubscriptionMatcher();

        assertTrue(matcher.test(URI.create("property"), URI.create("property")));
        assertFalse(matcher.test(URI.create("property"), URI.create("property2")));
    }
}