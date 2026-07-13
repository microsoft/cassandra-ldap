/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * NOTE: This file contains modifications to the original open source code
 */
package com.instaclustr.cassandra.ldap.ssl;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

/**
 * {@link SSLSocketFactory} that trusts only the certificates contained in a dedicated LDAP truststore,
 * so the LDAP(S) connection no longer relies on the JVM-default (JMX-shared) truststore.
 * <p>
 * JNDI can only instantiate a socket factory through the no-arg {@link #getDefault()} method, so the
 * truststore location and password are passed out-of-band via system properties:
 * <ul>
 *     <li>{@value #TRUSTSTORE_PATH_PROPERTY}</li>
 *     <li>{@value #TRUSTSTORE_PASSWORD_PROPERTY}</li>
 * </ul>
 * The factory fails closed: if the truststore path property is set but the file cannot be loaded, an
 * exception is thrown and there is no fallback to the JVM-default truststore.
 */
public class LdapSSLSocketFactory extends SSLSocketFactory
{
    public static final String TRUSTSTORE_PATH_PROPERTY = "com.instaclustr.cassandra.ldap.truststore";
    public static final String TRUSTSTORE_PASSWORD_PROPERTY = "com.instaclustr.cassandra.ldap.truststore.password";

    private static final String[] ENABLED_PROTOCOLS = { "TLSv1.2", "TLSv1.3" };
    private static final String ENDPOINT_IDENTIFICATION_ALGORITHM = "LDAPS";

    private final SSLSocketFactory delegate;

    public LdapSSLSocketFactory()
    {
        this.delegate = buildContext().getSocketFactory();
    }

    private static SSLContext buildContext()
    {
        final String truststorePath = System.getProperty(TRUSTSTORE_PATH_PROPERTY);
        final String truststorePassword = System.getProperty(TRUSTSTORE_PASSWORD_PROPERTY);

        if (truststorePath == null || truststorePath.trim().isEmpty())
        {
            throw new IllegalStateException(
                "LDAP truststore system property " + TRUSTSTORE_PATH_PROPERTY + " is not set; cannot initialize LdapSSLSocketFactory.");
        }

        final char[] password = truststorePassword == null ? null : truststorePassword.toCharArray();

        try
        {
            final KeyStore keyStore = loadKeyStore(truststorePath, password);

            final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            return sslContext;
        }
        catch (final Exception ex)
        {
            // Fail closed: never fall back to the JVM-default truststore on a configured-but-broken truststore.
            throw new IllegalStateException("Failed to initialize LDAP TLS truststore from " + truststorePath, ex);
        }
    }

    private static KeyStore loadKeyStore(final String path, final char[] password)
        throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException
    {
        // Try PKCS12 first, fall back to JKS.
        try
        {
            return loadKeyStore("PKCS12", path, password);
        }
        catch (final Exception pkcs12Failure)
        {
            try
            {
                return loadKeyStore("JKS", path, password);
            }
            catch (final Exception jksFailure)
            {
                // Surface the original PKCS12 failure as the primary cause.
                throw new KeyStoreException("Unable to load LDAP truststore as PKCS12 or JKS: " + path, pkcs12Failure);
            }
        }
    }

    private static KeyStore loadKeyStore(final String type, final String path, final char[] password)
        throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException
    {
        final KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = new FileInputStream(path))
        {
            keyStore.load(in, password);
        }
        return keyStore;
    }

    public static SocketFactory getDefault()
    {
        return SingletonHolder.INSTANCE;
    }

    private static final class SingletonHolder
    {
        private static final LdapSSLSocketFactory INSTANCE = new LdapSSLSocketFactory();
    }

    private Socket configure(final Socket socket)
    {
        if (socket instanceof SSLSocket)
        {
            final SSLSocket sslSocket = (SSLSocket) socket;
            sslSocket.setEnabledProtocols(ENABLED_PROTOCOLS.clone());

            final SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(ENDPOINT_IDENTIFICATION_ALGORITHM);
            sslSocket.setSSLParameters(parameters);
        }
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites()
    {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites()
    {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(final Socket s, final String host, final int port, final boolean autoClose) throws IOException
    {
        return configure(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(final String host, final int port) throws IOException
    {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(final String host, final int port, final InetAddress localHost, final int localPort) throws IOException
    {
        return configure(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port) throws IOException
    {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(final InetAddress address, final int port, final InetAddress localAddress, final int localPort) throws IOException
    {
        return configure(delegate.createSocket(address, port, localAddress, localPort));
    }
}
