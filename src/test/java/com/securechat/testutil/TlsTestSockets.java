package com.securechat.testutil;

import com.securechat.security.TlsContextFactory;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;

public final class TlsTestSockets {
    private TlsTestSockets() {
    }

    public static SSLSocket connect(String host, int port) throws Exception {
        SSLSocket socket = (SSLSocket) TlsContextFactory
                .createDefaultClientContext()
                .getSocketFactory()
                .createSocket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        socket.setSoTimeout(5_000);
        socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }
}
