/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.plugin.jdbc;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;

class JdbcMessageSupplier extends MessageSupplier {

    private final @Nullable String sql;

    // parameters and batchedParameters cannot both be non-null

    // cannot use ImmutableList for parameters since it can contain null elements
    private final @Nullable BindParameterList parameters;
    private final @Nullable ImmutableList<BindParameterList> batchedParameters;

    // this is only used for batching of non-PreparedStatements
    private final @Nullable ImmutableList<String> batchedSqls;

    private final RecordCountObject recordCountObject = new RecordCountObject();

    static JdbcMessageSupplier create(String sql) {
        return new JdbcMessageSupplier(sql, null, null, null);
    }

    static JdbcMessageSupplier createWithParameters(PreparedStatementMirror mirror) {
        return new JdbcMessageSupplier(mirror.getSql(), mirror.getParametersCopy(), null, null);
    }

    static JdbcMessageSupplier createWithBatchedSqls(StatementMirror mirror) {
        return new JdbcMessageSupplier(null, null, null, mirror.getBatchedSqlCopy());
    }

    static JdbcMessageSupplier createWithBatchedParameters(PreparedStatementMirror mirror) {
        return new JdbcMessageSupplier(mirror.getSql(), null, mirror.getBatchedParametersCopy(),
                null);
    }

    private JdbcMessageSupplier(@Nullable String sql, @Nullable BindParameterList parameters,
            @Nullable ImmutableList<BindParameterList> batchedParameters,
            @Nullable ImmutableList<String> batchedSqls) {
        if (sql == null && batchedSqls == null) {
            throw new AssertionError("Constructor args 'sql' and 'batchedSqls' cannot both"
                    + " be null (enforced by static factory methods)");
        }
        this.sql = sql;
        this.parameters = parameters;
        this.batchedParameters = batchedParameters;
        this.batchedSqls = batchedSqls;
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
            throw new AssertionError("Fields 'sql' and 'batchedSqls' cannot both be null"
                    + " (enforced by static factory methods)");
        }
        if (isUsingBatchedParameters() && batchedParameters.size() > 1) {
            // print out number of batches to make it easy to identify
            sb.append(Integer.toString(batchedParameters.size()));
            sb.append(" x ");
        }
        sb.append(sql);
        appendParameters(sb);
        appendRowCount(sb);
        return Message.from(sb.toString());
    }

    RecordCountObject getRecordCountObject() {
        return recordCountObject;
    }

    @EnsuresNonNullIf(expression = "parameters", result = true)
    private boolean isUsingParameters() {
        return parameters != null;
    }

    @EnsuresNonNullIf(expression = "batchedParameters", result = true)
    private boolean isUsingBatchedParameters() {
        return batchedParameters != null;
    }

    private void appendParameters(StringBuilder sb) {
        if (isUsingParameters() && !parameters.isEmpty()) {
            appendParameters(sb, parameters);
        } else if (isUsingBatchedParameters()) {
            for (BindParameterList oneParameters : batchedParameters) {
                appendParameters(sb, oneParameters);
            }
        }
    }

    private void appendRowCount(StringBuilder sb) {
        if (!recordCountObject.hasPerformedNavigation()) {
            return;
        }
        sb.append(" => ");
        int numRows = recordCountObject.getNumRows();
        sb.append(numRows);
        if (numRows == 1) {
            sb.append(" row");
        } else {
            sb.append(" rows");
        }
    }

    private static void appendBatchedSqls(StringBuilder sb, ImmutableList<String> batchedSqls) {
        boolean first = true;
        for (String batchedSql : batchedSqls) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(batchedSql);
            first = false;
        }
    }

    private static void appendParameters(StringBuilder sb, BindParameterList parameters) {
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
