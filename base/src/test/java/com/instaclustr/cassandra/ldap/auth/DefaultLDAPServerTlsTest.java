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
package com.instaclustr.cassandra.ldap.auth;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import java.util.Hashtable;
import java.util.Properties;

import com.instaclustr.cassandra.ldap.conf.LdapAuthenticatorConfiguration;
import com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

public class DefaultLDAPServerTlsTest
{
    private static final String SOCKET_FACTORY_KEY = "java.naming.ldap.factory.socket";

    @AfterMethod
    public void clearProperties()
    {
        System.clearProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY);
        System.clearProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY);
    }

    @Test
    public void truststoreAbsentLeavesEnvUnchanged()
    {
        final Properties properties = new Properties();
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP, "ldaps://ldap.example.com:636");

        final Hashtable<String, String> env = new Hashtable<>();
        DefaultLDAPServer.applyLdapTls(env, properties);

        assertFalse(env.containsKey(SOCKET_FACTORY_KEY));
        assertFalse(env.containsKey(SOCKET_FACTORY_KEY));
    }

    @Test
    public void truststorePresentWithLdapsWiresSocketFactory()
    {
        final Properties properties = new Properties();
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP, "ldaps://ldap.example.com:636");
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PROP, "/etc/cassandra/ldap-truststore.p12");
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PASSWORD_PROP, "secret");

        final Hashtable<String, String> env = new Hashtable<>();
        DefaultLDAPServer.applyLdapTls(env, properties);

        assertEquals(env.get(SOCKET_FACTORY_KEY), "com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory");
        assertEquals(System.getProperty(LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY), "/etc/cassandra/ldap-truststore.p12");
        assertEquals(System.getProperty(LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY), "secret");
    }

    @Test
    public void plainLdapIsSkipped()
    {
        final Properties properties = new Properties();
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP, "ldap://ldap.example.com:389");
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PROP, "/etc/cassandra/ldap-truststore.p12");
        properties.setProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PASSWORD_PROP, "secret");

        final Hashtable<String, String> env = new Hashtable<>();
        DefaultLDAPServer.applyLdapTls(env, properties);

        assertFalse(env.containsKey(SOCKET_FACTORY_KEY));
    }
}
