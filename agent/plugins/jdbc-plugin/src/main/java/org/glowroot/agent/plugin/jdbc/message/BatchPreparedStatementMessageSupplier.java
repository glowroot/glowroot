/*
 * Copyright 2015-2017 the original author or authors.
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

    // default is 512k characters so that memory limit is 1mb since 1 character = 2 bytes
    //
    // this same property is used to define similar limit in org.glowroot.agent.plugin.api.Message
    private static final int MESSAGE_CHAR_LIMIT =
            Integer.getInteger("glowroot.message.char.limit", 512 * 1024);

    private final Collection<BindParameterList> batchedParameters;
    private final int batchSize;

    public BatchPreparedStatementMessageSupplier(Collection<BindParameterList> batchedParameters,
            int batchSize) {
        this.batchedParameters = batchedParameters;
        this.batchSize = batchSize;
    }

    @Override
    public QueryMessage get() {
        // not using size() since capturedBatchSize is ConcurrentLinkedQueue
        int capturedBatchSize = 0;
        String suffix;
        if (batchedParameters.isEmpty()) {
            suffix = "";
        } else {
            StringBuilder sb = new StringBuilder();
            boolean exceededMessageCharLimit = false;
            for (BindParameterList oneParameters : batchedParameters) {
                PreparedStatementMessageSupplier.appendParameters(sb, oneParameters);
                capturedBatchSize++;
                if (sb.length() > MESSAGE_CHAR_LIMIT) {
                    sb.setLength(MESSAGE_CHAR_LIMIT);
                    sb.append(" [truncated to ");
                    sb.append(MESSAGE_CHAR_LIMIT);
                    sb.append(" characters]");
                    exceededMessageCharLimit = true;
                    break;
                }
            }
            if (!exceededMessageCharLimit && batchSize > capturedBatchSize) {
                sb.append(" ...");
            }
            suffix = sb.toString();
        }
        String prefix;
        if (batchSize > 1) {
            // print out number of batches to make it easy to identify
            prefix = "jdbc execution: " + batchSize + " x ";
        } else {
            prefix = "jdbc execution: ";
        }
        return QueryMessage.create(prefix, suffix);
    }
}
