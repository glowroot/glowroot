/**
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.plugin.jdbc;

import io.informant.api.Message;
import io.informant.api.MessageSupplier;
import io.informant.shaded.google.common.collect.ImmutableList;

import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.AssertNonNullIfTrue;
import checkers.nullness.quals.Nullable;

/**
 * Jdbc span captured by AspectJ pointcut.
 * 
 * Objects in the parameter array, batchedParameters collections and batchedSqls collection aren't
 * necessarily thread safe so users of this class must adhere to the following contract:
 * 
 * 1. The arrays / collections should not be modified after construction.
 * 
 * 2. None of the elements in these arrays / collections should be modified after construction.
 * 
 * 3. There should be some kind of coordination between the threads to ensure visibility of the
 * objects in these arrays / collections. In Informant's case, one thread is putting the
 * JdbcMessageSupplier on to a concurrent queue (right after construction) and the only way another
 * thread can get access to it is by reading it from the queue. The concurrent queue stores the
 * instance in a volatile field and so the coordination of the first thread writing to that volatile
 * field and the second thread reading from that volatile field ensures a happens-before
 * relationship which guarantees that the second thread sees everything that was done to the objects
 * in the parameters array prior to the first thread putting the span into the concurrent queue.
 * 
 * numRows is marked volatile to ensure visibility to other threads since it is updated after
 * putting the JdbcMessageSupplier on to the concurrent queue and so these updates cannot piggyback
 * on the happens-before relationship created by the concurrent queue.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class JdbcMessageSupplier extends MessageSupplier {

    private static final int NEXT_HAS_NOT_BEEN_CALLED = -1;

    @Nullable
    private final String sql;

    // parameters and batchedParameters cannot both be non-null
    @Nullable
    private final List</*@Nullable*/Object> parameters;
    @Nullable
    private final ImmutableList<List</*@Nullable*/Object>> batchedParameters;

    // this is only used for batching of non-PreparedStatements
    @Nullable
    private final ImmutableList<String> batchedSqls;

    @Nullable
    private final Integer connectionHashCode;

    private volatile int numRows = NEXT_HAS_NOT_BEEN_CALLED;

    static JdbcMessageSupplier create(String sql, @Nullable Integer connectionHashCode) {
        return new JdbcMessageSupplier(sql, null, null, null, connectionHashCode);
    }

    static JdbcMessageSupplier createWithParameters(PreparedStatementMirror mirror,
            @Nullable Integer connectionHashCode) {
        return new JdbcMessageSupplier(mirror.getSql(), mirror.getParametersCopy(), null, null,
                connectionHashCode);
    }

    static JdbcMessageSupplier createWithBatchedSqls(StatementMirror mirror,
            @Nullable Integer connectionHashCode) {
        return new JdbcMessageSupplier(null, null, null, mirror.getBatchedSqlCopy(),
                connectionHashCode);
    }

    static JdbcMessageSupplier createWithBatchedParameters(PreparedStatementMirror mirror,
            @Nullable Integer connectionHashCode) {
        return new JdbcMessageSupplier(mirror.getSql(), null, mirror.getBatchedParametersCopy(),
                null, connectionHashCode);
    }

    private JdbcMessageSupplier(@Nullable String sql,
            @Nullable List</*@Nullable*/Object> parameters,
            @Nullable ImmutableList<List</*@Nullable*/Object>> batchedParameters,
            @Nullable ImmutableList<String> batchedSqls, @Nullable Integer connectionHashCode) {

        if (sql == null && batchedSqls == null) {
            throw new NullPointerException("Constructor args 'sql' and 'batchedSqls' cannot both"
                    + " be null (enforced by static factory methods)");
        }
        this.sql = sql;
        this.parameters = parameters;
        this.batchedParameters = batchedParameters;
        this.batchedSqls = batchedSqls;
        this.connectionHashCode = connectionHashCode;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc execution: ");
        if (batchedSqls != null) {
            appendBatchedSqls(sb, batchedSqls);
            return Message.from(sb.toString());
        }
        if (sql == null) {
            throw new NullPointerException("Fields 'sql' and 'batchedSqls' cannot both be null"
                    + " (enforced by static factory methods)");
        }
        if (isUsingBatchedParameters() && batchedParameters.size() > 1) {
            // print out number of batches to make it easy to identify
            sb.append(Integer.toString(batchedParameters.size()));
            sb.append(" x ");
        }
        int numArgs = (numRows == NEXT_HAS_NOT_BEEN_CALLED) ? 1 : 2;
        String[] args = new String[numArgs];
        sb.append("{}");
        args[0] = sql;
        appendParameters(sb);
        appendRowCount(sb, args);
        appendConnectionHashCode(sb);
        return Message.from(sb.toString(), args);
    }

    // TODO put row num and bind parameters in detail map?

    void setHasPerformedNext() {
        if (numRows == NEXT_HAS_NOT_BEEN_CALLED) {
            numRows = 0;
        }
    }

    void setNumRows(int numRows) {
        this.numRows = numRows;
    }

    @AssertNonNullIfTrue("parameters")
    private boolean isUsingParameters() {
        return parameters != null;
    }

    @AssertNonNullIfTrue("batchedParameters")
    private boolean isUsingBatchedParameters() {
        return batchedParameters != null;
    }

    private void appendParameters(StringBuilder sb) {
        if (isUsingParameters() && !parameters.isEmpty()) {
            appendParameters(sb, parameters);
        } else if (isUsingBatchedParameters()) {
            for (List</*@Nullable*/Object> oneParameters : batchedParameters) {
                appendParameters(sb, oneParameters);
            }
        }
    }

    private void appendRowCount(StringBuilder sb, String[] args) {
        if (numRows == NEXT_HAS_NOT_BEEN_CALLED) {
            return;
        }
        sb.append(" => {}");
        if (numRows == 1) {
            sb.append(" row");
        } else {
            sb.append(" rows");
        }
        args[1] = Integer.toString(numRows);
    }

    private void appendConnectionHashCode(StringBuilder sb) {
        sb.append(" [connection: ");
        if (connectionHashCode == null) {
            sb.append("???");
        } else {
            sb.append(Integer.toHexString(connectionHashCode));
        }
        sb.append("]");
    }

    private static void appendBatchedSqls(StringBuilder sb, @ReadOnly List<String> batchedSqls) {
        boolean first = true;
        for (String batchedSql : batchedSqls) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(batchedSql);
            first = false;
        }
    }

    private static void appendParameters(StringBuilder sb, List</*@Nullable*/Object> parameters) {
        sb.append(" [");
        boolean first = true;
        for (Object parameter : parameters) {
            if (!first) {
                sb.append(", ");
            }
            if (parameter instanceof String) {
                sb.append("\'");
                sb.append((String) parameter);
                sb.append("\'");
            } else {
                sb.append(String.valueOf(parameter));
            }
            first = false;
        }
        sb.append("]");
    }
}
