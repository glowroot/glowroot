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

import java.util.Collection;

import org.glowroot.api.Message;

public class BatchPreparedStatementMessageSupplier extends JdbcMessageSupplier {

    private final String sql;

    private final Collection<BindParameterList> batchedParameters;

    public BatchPreparedStatementMessageSupplier(String sql,
            Collection<BindParameterList> batchedParameters) {
        this.sql = sql;
        this.batchedParameters = batchedParameters;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc execution: ");
        int batchSize = batchedParameters.size();
        if (batchSize > 1) {
            // print out number of batches to make it easy to identify
            sb.append(batchSize);
            sb.append(" x ");
        }
        sb.append(sql);
        for (BindParameterList oneParameters : batchedParameters) {
            PreparedStatementMessageSupplier.appendParameters(sb, oneParameters);
        }
        appendRowCount(sb);
        return Message.from(sb.toString());
    }
}
