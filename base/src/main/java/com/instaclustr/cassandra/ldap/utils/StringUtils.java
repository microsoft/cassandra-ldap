/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 */
package com.instaclustr.cassandra.ldap.utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

public class StringUtils {
    public static String stripWhitespacesAndQuotes(String value)
    {
        return value.trim().replaceAll("^\"|\"$", ""); //Strips whitespace and quotes from the beginning and end
    }
    
    public static String getStackTrace(Exception e)
    {   
        String stackTrace = "";
       
        try( StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter))
        {
            e.printStackTrace(printWriter);
            printWriter.flush();

            stackTrace = stringWriter.toString();
        }
        catch (IOException ioe) 
        {
        }
        
        return stackTrace;
    }
}
