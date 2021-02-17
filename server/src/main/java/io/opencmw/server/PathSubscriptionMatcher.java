package io.opencmw.server;

import java.net.URI;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.NotNull;

/**
 * basic path-only predicate to match notified topics to requested/subscriber topics
 *
 * @author rstein
 */
public class PathSubscriptionMatcher implements BiPredicate<URI, URI> {
    @Override
    public boolean test(final @NotNull URI notified, final @NotNull URI subscriber) {
        // N.B. for the time being only the path is matched - TODO: upgrade to full topic matching
        return notified.getPath().startsWith(subscriber.getPath()) || subscriber.getPath().isBlank();
    }
}
