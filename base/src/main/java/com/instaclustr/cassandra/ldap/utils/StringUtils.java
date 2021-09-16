/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 */
package com.instaclustr.cassandra.ldap.utils;

public class StringUtils {
    public static String stripWhitespacesAndQuotes(String value)
    {
        return value.trim().replaceAll("^\"|\"$", ""); //Strips whitespace and quotes from the beginning and end
    }
}
