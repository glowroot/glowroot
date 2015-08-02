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

import java.util.List;

import org.glowroot.plugin.api.transaction.Message;
import org.glowroot.plugin.api.transaction.MessageSupplier;

public class BatchStatementMessageSupplier extends MessageSupplier {

    private final List<String> batchedSqls;

    public BatchStatementMessageSupplier(List<String> batchedSqls) {
        this.batchedSqls = batchedSqls;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc execution: ");
        if (batchedSqls.isEmpty()) {
            sb.append("(empty batch)");
        }
        boolean first = true;
        for (String batchedSql : batchedSqls) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(batchedSql);
            first = false;
        }
        return Message.from(sb.toString());
    }
}
