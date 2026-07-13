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
package com.instaclustr.cassandra.ldap.auth;

import static java.lang.String.format;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.instaclustr.cassandra.ldap.User;
import com.instaclustr.cassandra.ldap.auth.DefaultLDAPServer.LDAPInitialContext.CloseableLdapContext;
import com.instaclustr.cassandra.ldap.conf.LdapAuthenticatorConfiguration;
import com.instaclustr.cassandra.ldap.exception.LDAPAuthFailedException;
import com.instaclustr.cassandra.ldap.hash.Hasher;
import com.instaclustr.cassandra.ldap.utils.StringUtils;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.ExceptionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultLDAPServer extends LDAPUserRetriever
{
    private static final Logger logger = LoggerFactory.getLogger(DefaultLDAPServer.class);

    static final String LDAP_SSL_SOCKET_FACTORY_CLASS = "com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory";

    /**
     * Routes the LDAP(S) connection through {@link com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory} using the
     * dedicated LDAP truststore, when configured. Only applies when the provider URL is {@code ldaps://}.
     * <p>
     * If no truststore is configured, the environment is left untouched so the connection keeps using the JVM default
     * (preserving rollback / no-cert behavior). This is the only permitted fallback.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void applyLdapTls(final Map env, final Properties properties)
    {
        final String providerUrl = properties.getProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP);

        if (providerUrl == null || !providerUrl.trim().toLowerCase().startsWith("ldaps://"))
        {
            return;
        }

        final String truststorePath = properties.getProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PROP);

        if (truststorePath == null || truststorePath.trim().isEmpty())
        {
            return;
        }

        final String truststorePassword = properties.getProperty(LdapAuthenticatorConfiguration.LDAP_TRUSTSTORE_PASSWORD_PROP);

        System.setProperty(com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory.TRUSTSTORE_PATH_PROPERTY, truststorePath);
        if (truststorePassword != null)
        {
            System.setProperty(com.instaclustr.cassandra.ldap.ssl.LdapSSLSocketFactory.TRUSTSTORE_PASSWORD_PROPERTY, truststorePassword);
        }

        env.put("java.naming.ldap.factory.socket", LDAP_SSL_SOCKET_FACTORY_CLASS);
    }

    static class LDAPInitialContext implements AutoCloseable
    {
        protected static final Logger logger = LoggerFactory.getLogger(LDAPInitialContext.class);

        protected CloseableLdapContext ldapContext;
        protected Properties properties;
  
        public LDAPInitialContext(final Properties properties)
        {
            this.properties = properties;

            final Properties ldapProperties = new Properties();
            
            ldapProperties.put(Context.INITIAL_CONTEXT_FACTORY, properties.getProperty(LdapAuthenticatorConfiguration.CONTEXT_FACTORY_PROP));
            ldapProperties.put(Context.PROVIDER_URL, properties.getProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP));
            applyLdapTls(ldapProperties, properties);
            this.populateLdapUserInfo(ldapProperties);
            
            try
            {
                ldapContext = new CloseableLdapContext(new InitialDirContext(ldapProperties));
            }
            catch (final NamingException ex)
            {
                throw new ConfigurationException(format("Failed to connect to LDAP server: %s, explanation: %s",
                                                        ex.getMessage(),
                                                        ex.getExplanation() == null ? "uknown" : ex.getExplanation()),
                                                 ex);
            }
        }

        public void populateLdapUserInfo(Properties ldapProperties)
        {
            final String serviceDN = this.properties.getProperty(LdapAuthenticatorConfiguration.LDAP_DN);
            final String servicePass = this.properties.getProperty(LdapAuthenticatorConfiguration.PASSWORD_KEY);

            ldapProperties.put(Context.SECURITY_PRINCIPAL, serviceDN);
            ldapProperties.put(Context.SECURITY_CREDENTIALS, servicePass);
        }
        
        public static final class CloseableLdapContext implements AutoCloseable
        {
            private final InitialDirContext context;

            CloseableLdapContext(final InitialDirContext context)
            {
                this.context = context;
            }

            @Override
            public void close() throws Exception
            {
                if (context != null)
                {
                    context.close();
                }
            }
        }

        public String searchLdapDN(final String username) throws NamingException
        {
            final String filterTemplate = properties.getProperty(LdapAuthenticatorConfiguration.FILTER_TEMPLATE);
            final String filter = format(filterTemplate, username);

            logger.debug(String.format("User name is %s, going to use filter: %s", username, filter));

            String dn = null;

            final SearchControls searchControls = new SearchControls();
            searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);

            NamingEnumeration<SearchResult> answer = null;

            try
            {

                answer = ldapContext.context.search("", filter, searchControls);

                final List<String> resolvedDns = new ArrayList<>();

                if (answer.hasMore())
                {
                    SearchResult result = answer.next();
                    dn = result.getNameInNamespace();
                    resolvedDns.add(dn);
                }

                if (resolvedDns.size() != 1 || resolvedDns.get(0) == null)
                {
                    throw new NamingException(String.format("There is not one DN resolved after search on filter %s: %s. "
                                                                + "User likely does not exist or connection to LDAP server is invalid.", filter, resolvedDns));
                }

                logger.debug("Returning DN: " + resolvedDns.get(0));

                return dn;
            } catch (final NamingException ex)
            {
                if (answer != null)
                {
                    try
                    {
                        answer.close();
                    } catch (NamingException closingException)
                    {
                        logger.warn("Failing to close connection to LDAP server.");
                    }
                }

                logger.error("Error while searching! " + ex.toString(true) + " explanation: " + ex.getExplanation(), ex);

                throw ex;
            }
        }

        @Override
        public void close() throws IOException {
            if (ldapContext != null)
            {
                try
                {
                    ldapContext.close();
                }
                catch (final Exception ex)
                {
                    throw new IOException(ex);
                }
            }
        }
    }

    @Override
    public UserRetriever setup(final Hasher hasher, final Properties properties) throws ConfigurationException
    {
        this.properties = properties;
        this.hasher = hasher;
        return this;
    }

    public LDAPInitialContext getLdapContext()
    {
        return new LDAPInitialContext(this.properties);
    }
    
    @Override
    public User retrieve(User user) throws LDAPAuthFailedException
    {
        try (final LDAPInitialContext context = this.getLdapContext())
        {
            final String ldapDn = context.searchLdapDN(user.getUsername());

            logger.debug(String.format("Resolved LDAP DN: %s", ldapDn));

            final Hashtable<String, String> env = getUserEnv(ldapDn,
                                                             user.getPassword(),
                                                             properties.getProperty(LdapAuthenticatorConfiguration.CONTEXT_FACTORY_PROP),
                                                             properties.getProperty(LdapAuthenticatorConfiguration.LDAP_URI_PROP));

            try (final CloseableLdapContext ldapContext = new CloseableLdapContext(new InitialDirContext(env)))
            {
                logger.debug("Logging to LDAP with {} was ok!", user.toString());

                final User foundUser = new User(user.getUsername(),
                                          hasher.hashPassword(user.getPassword(),
                                                              LdapAuthenticatorConfiguration.getGensaltLog2Rounds(this.properties)));
                foundUser.setLdapDN(ldapDn);

                return foundUser;
            }
            catch (final NamingException ex)
            {
                String detailedMsg = "Failed to login. Message: "+ ex.getMessage() 
                    + ", Code: " + ExceptionCode.BAD_CREDENTIALS.name() 
                    + ", Reason: " + ex.getCause() 
                    + ", Stack trace: " + StringUtils.getStackTrace(ex);               
                logger.error(detailedMsg);
                
                throw new LDAPAuthFailedException(ExceptionCode.BAD_CREDENTIALS, ex.getMessage(), ex);
            }
        }
        catch (final Exception ex)
        {
            String detailedMsg = "Failed to login. Message: " 
                    + ex.getMessage() 
                    + ", Code: " + ExceptionCode.UNAUTHORIZED.name() 
                    + ", Reason: " + ex.getCause() 
                    + ", Stack trace: " + StringUtils.getStackTrace(ex);
            logger.error(detailedMsg);
            
            throw new LDAPAuthFailedException(ExceptionCode.UNAUTHORIZED, "Not possible to login " + user.getUsername(), ex);
        }
    }

    private Hashtable<String, String> getUserEnv(final String username,
                                                 final String password,
                                                 final String initialContextFactory,
                                                 final String ldapUri)
    {
        final Hashtable<String, String> env = new Hashtable<>(11);

        env.put(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
        env.put(Context.PROVIDER_URL, ldapUri);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");

        env.put(Context.SECURITY_PRINCIPAL, username);
        env.put(Context.SECURITY_CREDENTIALS, password);

        applyLdapTls(env, this.properties);

        return env;
    }
}
