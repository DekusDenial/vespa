// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.zookeeper;

import com.yahoo.security.tls.TlsContext;
import com.yahoo.security.tls.TransportSecurityUtils;

import javax.net.ssl.SSLContext;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Provider for Vespa {@link SSLContext} instance to Zookeeper + misc utility methods for providing Vespa TLS specific ZK configuration.
 *
 * @author bjorncs
 */
public class VespaSslContextProvider implements Supplier<SSLContext> {

    private static final TlsContext tlsContext = TransportSecurityUtils.getSystemTlsContext().orElse(null);

    @Override
    public SSLContext get() {
        if (!tlsEnabled()) throw new IllegalStateException("Vespa TLS is not enabled");
        return tlsContext.context();
    }

    public static boolean tlsEnabled() { return tlsContext != null; }

    public static String enabledTlsProtocolConfigValue() {
        return Arrays.stream(tlsContext.parameters().getProtocols())
                .sorted()
                .collect(Collectors.joining(","));
    }

    public static String enabledTlsCiphersConfigValue() {
        return Arrays.stream(tlsContext.parameters().getCipherSuites())
                .sorted()
                .collect(Collectors.joining(","));
    }

    public static String sslContextVersion() { return tlsContext.context().getProtocol(); }
}
