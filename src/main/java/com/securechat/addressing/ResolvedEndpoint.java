package com.securechat.addressing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/** Result of resolving a server hostname and port. */
public record ResolvedEndpoint(
        String requestedHost,
        String resolvedHostName,
        String ipAddress,
        int port,
        AddressScope scope,
        InetAddress inetAddress
) {
    public ResolvedEndpoint {
        Objects.requireNonNull(requestedHost);
        Objects.requireNonNull(resolvedHostName);
        Objects.requireNonNull(ipAddress);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(inetAddress);
    }

    public InetSocketAddress socketAddress() {
        return new InetSocketAddress(inetAddress, port);
    }

    public String displayName() {
        return requestedHost + " (" + ipAddress + "):" + port;
    }
}
