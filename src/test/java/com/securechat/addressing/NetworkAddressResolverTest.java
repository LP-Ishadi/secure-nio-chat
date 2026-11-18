package com.securechat.addressing;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAddressResolverTest {
    private final NetworkAddressResolver resolver = new NetworkAddressResolver();

    @Test
    void resolvesAndClassifiesLocalhost() throws Exception {
        ResolvedEndpoint endpoint = resolver.resolve("localhost", 5000);

        assertEquals("localhost", endpoint.requestedHost());
        assertEquals(5000, endpoint.port());
        assertEquals(AddressScope.LOOPBACK, endpoint.scope());
        assertTrue(endpoint.inetAddress().isLoopbackAddress());
        assertFalse(endpoint.ipAddress().isBlank());
        assertEquals(5000, endpoint.socketAddress().getPort());
    }

    @Test
    void classifiesCommonAddressScopes() throws Exception {
        assertEquals(
                AddressScope.LOOPBACK,
                resolver.classify(InetAddress.getByName("127.0.0.1"))
        );
        assertEquals(
                AddressScope.PRIVATE,
                resolver.classify(InetAddress.getByName("192.168.1.20"))
        );
        assertEquals(
                AddressScope.LINK_LOCAL,
                resolver.classify(InetAddress.getByName("169.254.10.20"))
        );
        assertEquals(
                AddressScope.UNSPECIFIED,
                resolver.classify(InetAddress.getByName("0.0.0.0"))
        );
    }

    @Test
    void listsAtLeastOneLocalAddress() throws Exception {
        List<InetAddress> addresses = resolver.localAddresses();

        assertFalse(addresses.isEmpty());
        assertTrue(addresses.stream().noneMatch(InetAddress::isAnyLocalAddress));
    }

    @Test
    void rejectsInvalidInputsAndUnknownHost() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(" ", 5000)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("localhost", 0)
        );
        assertThrows(
                UnknownHostException.class,
                () -> resolver.resolve("host-does-not-exist.invalid", 5000)
        );
    }
}
