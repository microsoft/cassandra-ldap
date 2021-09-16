/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 */

package com.instaclustr.cassandra.ldap.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.security.keyvault.secrets.SecretClient;

public class SecretUtils 
{    
    private static final Logger logger = LoggerFactory.getLogger(SecretUtils.class);

    public static String getSecretValue(SecretClient client, String secretName)
    {
        logger.info("Fetching Secret: {}",  secretName);

        return client.getSecret(secretName).getValue();
    }
}
