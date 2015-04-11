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
package org.glowroot.plugin.cassandra;

import org.glowroot.api.Message;
import org.glowroot.api.MessageSupplier;

class QueryMessageSupplier extends MessageSupplier {

    private final String query;

    // intentionally not volatile for performance, but it does mean partial and active trace
    // captures may see stale value (but partial and active trace captures use memory barrier in
    // Transaction to ensure the values are at least visible as of the end of the last trace entry)
    private int numRows = -1;

    QueryMessageSupplier(String query) {
        this.query = query;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("cql execution: ");
        sb.append(query);
        appendRowCount(sb);
        return Message.from(sb.toString());
    }

    public void setHasPerformedNavigation() {
        if (numRows == -1) {
            numRows = 0;
        }
    }

    public void incrementNumRows() {
        if (numRows == -1) {
            numRows = 1;
        } else {
            numRows++;
        }
    }

    public void updateNumRows(int currentRow) {
        numRows = Math.max(numRows, currentRow);
    }

    void appendRowCount(StringBuilder sb) {
        if (numRows == -1) {
            return;
        }
        sb.append(" => ");
        sb.append(numRows);
        if (numRows == 1) {
            sb.append(" row");
        } else {
            sb.append(" rows");
        }
    }
}
