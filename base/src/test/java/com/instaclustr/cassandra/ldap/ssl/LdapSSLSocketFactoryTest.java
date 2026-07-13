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
package com.instaclustr.cassandra.ldap.ssl;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

public class LdapSSLSocketFactoryTest
{
    private static final String PASSWORD = "changeit";

    @AfterMethod
    public void clearProperties()
    {
        System.clearProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY);
        System.clearProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY);
    }

    private static File writePkcs12Truststore() throws Exception
    {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, PASSWORD.toCharArray());

        final File file = Files.createTempFile("ldap-truststore", ".p12").toFile();
        file.deleteOnExit();
        try (OutputStream out = new FileOutputStream(file))
        {
            keyStore.store(out, PASSWORD.toCharArray());
        }
        return file;
    }

    @Test
    public void pkcs12LoadSuccessBuildsContext() throws Exception
    {
        final File truststore = writePkcs12Truststore();
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY, truststore.getAbsolutePath());
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY, PASSWORD);

        final LdapSSLSocketFactory factory = new LdapSSLSocketFactory();

        assertTrue(factory.getSupportedCipherSuites().length > 0);
    }

    @Test
    public void missingFileFailsClosed()
    {
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY, "/does/not/exist/truststore.p12");
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY, PASSWORD);

        try
        {
            new LdapSSLSocketFactory();
            fail("Expected initialization to fail closed on a missing truststore file");
        }
        catch (final IllegalStateException expected)
        {
            // fail closed - never silently fall back to JVM default
        }
    }

    @Test
    public void createdSocketHasOnlyTls12And13AndLdapsEndpointId() throws Exception
    {
        final File truststore = writePkcs12Truststore();
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY, truststore.getAbsolutePath());
        System.setProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY, PASSWORD);

        final LdapSSLSocketFactory factory = new LdapSSLSocketFactory();

        // A connected (but not yet handshaked) loopback socket lets us inspect protocol/endpoint settings
        // without performing a real TLS handshake.
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()))
        {
            final Socket underlying = new Socket(server.getInetAddress(), server.getLocalPort());
            final SSLSocket sslSocket = (SSLSocket) factory.createSocket(underlying, "localhost", server.getLocalPort(), true);

            final Set<String> enabled = new HashSet<>(Arrays.asList(sslSocket.getEnabledProtocols()));
            assertEquals(enabled, new HashSet<>(Arrays.asList("TLSv1.2", "TLSv1.3")));

            assertEquals(sslSocket.getSSLParameters().getEndpointIdentificationAlgorithm(), "LDAPS");

            sslSocket.close();
        }
    }
}
