package io.opencmw.server;

import java.net.URI;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;

import io.opencmw.OpenCmwProtocol;
import io.opencmw.domain.BinaryData;
import io.opencmw.domain.NoData;
import io.opencmw.rbac.RbacRole;
import io.opencmw.utils.Cache;

public class ClipboardWorker extends MajordomoWorker<NoData, BinaryData, BinaryData> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardWorker.class);
    private static final NoData EMPTY_CONTEXT = new NoData();
    private final String serviceName;
    private final Cache<String, BinaryData> repository;

    public ClipboardWorker(final @NotNull String serviceName, final @NotNull ZContext ctx, final Cache<String, BinaryData> repository, final @NotNull RbacRole<?>... rbacRoles) {
        super(ctx, serviceName, NoData.class, BinaryData.class, BinaryData.class, rbacRoles);
        this.serviceName = serviceName;
        this.repository = Objects.requireNonNullElse(repository, Cache.<String, BinaryData>builder().build());
        this.setHandler((rawCtx, reqCtx, in, repCtx, out) -> {
            switch (rawCtx.req.command) {
            case GET_REQUEST:
                handleGet(rawCtx, reqCtx, in, repCtx, out);
                break;
            case SET_REQUEST:
                handleSet(rawCtx, reqCtx, in, repCtx, out);
                break;
            case UNKNOWN:
                throw new UnsupportedOperationException("command not implemented by service: '" + serviceName + "' request message: " + rawCtx.req);
            }
        });
        this.setDaemon(true);
    }

    private void handleGet(final OpenCmwProtocol.Context rawCtx, final NoData reqCtx, final BinaryData in, final NoData repCtx, final BinaryData out) {
        final URI uri = rawCtx.req.topic;
        final String resource = StringUtils.stripStart(uri.getPath(), getServiceName());
        final BinaryData data = Objects.requireNonNull(repository.get(resource), "could not retrieve resource '" + resource + "'");

        out.resourceName = data.resourceName;
        out.contentType = data.contentType;
        out.data = data.data;
        out.dataSize = data.dataSize;
    }

    private void handleSet(final OpenCmwProtocol.Context rawCtx, final NoData reqCtx, final BinaryData in, final NoData repCtx, final BinaryData out) {
        final URI uri = rawCtx.req.topic;
        final String cleanedResourceName = StringUtils.prependIfMissing(in.resourceName, "/");

        // add binary data to repository
        repository.put(cleanedResourceName, in);

        // notify others that might be listening always as <serviceName>/resourceName ...
        // N.B. we do not cross-notify for other properties
        notify(cleanedResourceName, EMPTY_CONTEXT, in);
    }

    public String getServiceName() {
        return serviceName;
    }
}
