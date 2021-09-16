/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 */

package com.instaclustr.cassandra.ldap.auth;

import java.util.Properties;

import javax.naming.Context;

import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.instaclustr.cassandra.ldap.conf.LdapAuthenticatorConfiguration;
import com.instaclustr.cassandra.ldap.utils.SecretUtils;
import com.instaclustr.cassandra.ldap.utils.StringUtils;

public class CassandraMILdapServer extends DefaultLDAPServer 
{
    public class CassandraMILDAPInitialContext extends LDAPInitialContext 
    {         
        public CassandraMILDAPInitialContext(Properties properties)
        {
            super(properties);
        }
        
        @Override 
        public void populateLdapUserInfo(Properties ldapProperties)
        {           
            String serviceUserDnSecretName = StringUtils.stripWhitespacesAndQuotes(properties.getProperty(LdapAuthenticatorConfiguration.SERVICE_DN_SECRET_NAME));
            String servicePasswordSecretName = StringUtils.stripWhitespacesAndQuotes(properties.getProperty(LdapAuthenticatorConfiguration.SERVICE_PASSWORD_SECRET_NAME)); 
            String managedIdentityClientId = StringUtils.stripWhitespacesAndQuotes(properties.getProperty(LdapAuthenticatorConfiguration.MANAGED_IDENTITY_CLIENT_ID)); 
            String keyVaultUrl = StringUtils.stripWhitespacesAndQuotes(properties.getProperty(LdapAuthenticatorConfiguration.KEYVAULT_URL)); 

            ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder()
                    .clientId(managedIdentityClientId)
                    .build();

            SecretClient client = new SecretClientBuilder()
                .vaultUrl(keyVaultUrl)
                .credential(credential)
                .buildClient();
            
            final String serviceDN = SecretUtils.getSecretValue(client, serviceUserDnSecretName);
            final String servicePass = SecretUtils.getSecretValue(client, servicePasswordSecretName);

            ldapProperties.put(Context.SECURITY_PRINCIPAL, serviceDN);
            ldapProperties.put(Context.SECURITY_CREDENTIALS, servicePass);         
        }
    }
    
    @Override
    public LDAPInitialContext getLdapContext()
    {
        return new CassandraMILDAPInitialContext(this.properties);
    }
}

