/*
 * Copyright 2011-2015 the original author or authors.
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

import org.glowroot.api.MessageSupplier;

public abstract class JdbcMessageSupplier extends MessageSupplier {

    // intentionally not volatile for performance, but it does mean partial and active trace
    // captures may see stale value (but partial and active trace captures use memory barrier in
    // Transaction to ensure the values are at least visible as of the end of the last trace entry)
    private int numRows;

    private boolean hasPerformedNavigation;

    public void setHasPerformedNavigation() {
        hasPerformedNavigation = true;
    }

    public void incrementNumRows() {
        numRows++;
        hasPerformedNavigation = true;
    }

    public void updateNumRows(int currentRow) {
        numRows = Math.max(numRows, currentRow);
        hasPerformedNavigation = true;
    }

    void appendRowCount(StringBuilder sb) {
        if (!hasPerformedNavigation) {
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
