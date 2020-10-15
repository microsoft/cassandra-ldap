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

import java.util.Collections;

import com.instaclustr.cassandra.ldap.User;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ResultMessage.Rows;
import org.apache.cassandra.utils.ByteBufferUtil;

public class Cassandra311RolePasswordRetriever extends AbstractCassandraRolePasswordRetriever
{

    @Override
    public void init(ClientState clientState)
    {
        this.clientState = clientState;
        authenticateStatement = (SelectStatement) QueryProcessor.getStatement("SELECT salted_hash FROM system_auth.roles WHERE role = ?", clientState).statement;

        legacyTableExists = legacyCredentialsTableExists();

        if (legacyTableExists)
        {
            prepareLegacyAuthenticateStatementInternal(clientState);
        }
    }

    @Override
    protected Rows getRows(final User user)
    {
        return authenticationStatement(clientState, legacyTableExists).execute(QueryState.forInternalCalls(),
                                                                               QueryOptions.forInternalCalls(
                                                                                   consistencyForRole(user.getUsername()),
                                                                                   Collections.singletonList(ByteBufferUtil.bytes(user.getUsername()))),
                                                                               System.nanoTime());
    }

    @Override
    protected void prepareLegacyAuthenticateStatementInternal(final ClientState clientState)
    {
        final String query = String.format("SELECT salted_hash from %s.%s WHERE username = ?", AUTH_KEYSPACE, LEGACY_CREDENTIALS_TABLE);
        legacyAuthenticateStatement = (SelectStatement) QueryProcessor.getStatement(query, clientState).statement;
    }

    @Override
    protected boolean legacyCredentialsTableExists()
    {
        return Schema.instance.getCFMetaData(AUTH_KEYSPACE, LEGACY_CREDENTIALS_TABLE) != null;
    }

}
