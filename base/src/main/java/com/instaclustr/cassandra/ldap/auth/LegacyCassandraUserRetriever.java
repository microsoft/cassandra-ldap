package com.instaclustr.cassandra.ldap.auth;

import static java.util.Collections.singletonList;

import com.instaclustr.cassandra.ldap.User;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ResultMessage.Rows;
import org.apache.cassandra.utils.ByteBufferUtil;

public class LegacyCassandraUserRetriever extends AbstractCassandraUserRetriever
{

    @Override
    public void init(ClientState clientState)
    {
        this.clientState = clientState;

        authenticateStatement = (SelectStatement) QueryProcessor.getStatement("SELECT salted_hash FROM system_auth.roles WHERE role = ?", clientState);
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
                                                                               QueryOptions.forInternalCalls(consistencyForRole(user.getUsername()),
                                                                                                             singletonList(ByteBufferUtil.bytes(user.getUsername()))),
                                                                               System.nanoTime());
    }

    @Override
    protected void prepareLegacyAuthenticateStatementInternal(final ClientState clientState)
    {
        final String query = String.format("SELECT salted_hash from %s.%s WHERE username = ?", AUTH_KEYSPACE, LEGACY_CREDENTIALS_TABLE);
        legacyAuthenticateStatement = (SelectStatement) QueryProcessor.getStatement(query, clientState);
    }

    @Override
    protected boolean legacyCredentialsTableExists()
    {
        return Schema.instance.getTableMetadata(AUTH_KEYSPACE, LEGACY_CREDENTIALS_TABLE) != null;
    }
}
