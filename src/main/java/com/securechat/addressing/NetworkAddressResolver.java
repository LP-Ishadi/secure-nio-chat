package com.securechat.addressing;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Resolves hostnames and exposes useful local and remote address details. */
public final class NetworkAddressResolver {
    /** Resolves a hostname, preferring IPv4 when both IP families are present. */
    public ResolvedEndpoint resolve(String host, int port)
            throws UnknownHostException {
        validate(host, port);
        InetAddress[] candidates = InetAddress.getAllByName(host);
        InetAddress selected = Arrays.stream(candidates)
                .filter(Inet4Address.class::isInstance)
                .findFirst()
                .orElse(candidates[0]);

        return new ResolvedEndpoint(
                host,
                selected.getCanonicalHostName(),
                selected.getHostAddress(),
                port,
                classify(selected),
                selected
        );
    }

    /** Returns active local addresses, with loopback addresses listed last. */
    public List<InetAddress> localAddresses() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .filter(this::isUsableInterface)
                .flatMap(NetworkInterface::inetAddresses)
                .filter(address -> !address.isAnyLocalAddress())
                .sorted(Comparator
                        .comparing(InetAddress::isLoopbackAddress)
                        .thenComparing(InetAddress::getHostAddress))
                .toList();
    }

    /**
     * Asks the operating-system routing table which local address would be
     * used for this endpoint. Connecting this UDP socket sends no packet.
     */
    public InetAddress localAddressFor(ResolvedEndpoint endpoint)
            throws SocketException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(endpoint.inetAddress(), endpoint.port());
            return socket.getLocalAddress();
        }
    }

    public AddressScope classify(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return AddressScope.UNSPECIFIED;
        }
        if (address.isLoopbackAddress()) {
            return AddressScope.LOOPBACK;
        }
        if (address.isSiteLocalAddress()) {
            return AddressScope.PRIVATE;
        }
        if (address.isLinkLocalAddress()) {
            return AddressScope.LINK_LOCAL;
        }
        return AddressScope.PUBLIC;
    }

    private boolean isUsableInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp();
        } catch (SocketException exception) {
            return false;
        }
    }

    private static void validate(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
