/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.plugin.jdbc.message;

import com.google.common.collect.ImmutableList;

import org.glowroot.api.Message;

public class BatchPreparedStatementMessageSupplier extends JdbcMessageSupplier {

    private final String sql;

    private final ImmutableList<BindParameterList> batchedParameters;

    public BatchPreparedStatementMessageSupplier(String sql,
            ImmutableList<BindParameterList> batchedParameters) {
        this.sql = sql;
        this.batchedParameters = batchedParameters;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc execution: ");
        if (batchedParameters.size() > 1) {
            // print out number of batches to make it easy to identify
            sb.append(Integer.toString(batchedParameters.size()));
            sb.append(" x ");
        }
        sb.append(sql);
        for (BindParameterList oneParameters : batchedParameters) {
            appendParameters(sb, oneParameters);
        }
        appendRowCount(sb);
        return Message.from(sb.toString());
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
