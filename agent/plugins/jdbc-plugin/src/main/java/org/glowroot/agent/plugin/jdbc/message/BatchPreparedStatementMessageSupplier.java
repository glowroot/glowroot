/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc.message;

import java.util.Collection;

import org.glowroot.agent.plugin.api.QueryMessage;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;

public class BatchPreparedStatementMessageSupplier extends QueryMessageSupplier {

    private final Collection<BindParameterList> batchedParameters;

    public BatchPreparedStatementMessageSupplier(Collection<BindParameterList> batchedParameters) {
        this.batchedParameters = batchedParameters;
    }

    @Override
    public QueryMessage get() {
        String prefix;
        int batchSize = batchedParameters.size();
        if (batchSize > 1) {
            // print out number of batches to make it easy to identify
            prefix = "jdbc execution: " + batchSize + " x ";
        } else {
            prefix = "jdbc execution: ";
        }
        String suffix;
        if (batchedParameters.isEmpty()) {
            suffix = "";
        } else {
            StringBuilder sb = new StringBuilder();
            for (BindParameterList oneParameters : batchedParameters) {
                PreparedStatementMessageSupplier.appendParameters(sb, oneParameters);
            }
            suffix = sb.toString();
        }
        return QueryMessage.create(prefix, suffix);
    }
}
