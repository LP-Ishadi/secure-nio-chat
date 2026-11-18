package com.securechat.security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** Creates configured TLS contexts for the server and clients. */
public final class TlsContextFactory {
    public static final Path DEFAULT_KEYSTORE = Path.of(
            "config", "certs", "server-keystore.p12"
    );
    public static final Path DEFAULT_TRUSTSTORE = Path.of(
            "config", "certs", "client-truststore.p12"
    );
    public static final String DEFAULT_PASSWORD = "changeit";

    private TlsContextFactory() {
    }

    public static SSLContext createServerContext(
            Path keyStorePath,
            char[] password
    ) throws IOException {
        try {
            KeyStore keyStore = loadStore(keyStorePath, password);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm()
            );
            keyManagers.init(keyStore, password);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not initialize server TLS context", exception);
        }
    }

    public static SSLContext createClientContext(
            Path trustStorePath,
            char[] password
    ) throws IOException {
        try {
            KeyStore trustStore = loadStore(trustStorePath, password);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagers.init(trustStore);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not initialize client TLS context", exception);
        }
    }

    public static SSLContext createDefaultServerContext() throws IOException {
        return createServerContext(
                DEFAULT_KEYSTORE,
                DEFAULT_PASSWORD.toCharArray()
        );
    }

    public static SSLContext createDefaultClientContext() throws IOException {
        return createClientContext(
                DEFAULT_TRUSTSTORE,
                DEFAULT_PASSWORD.toCharArray()
        );
    }

    private static KeyStore loadStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }
}
