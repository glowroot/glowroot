/*
 * Copyright 2014 the original author or authors.
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

class RecordCountObject {

    // intentionally not for performance, but it does mean partial and active trace
    // captures may see stale value (but partial and active trace captures use memory barrier in
    // Trace to ensure the values are at least visible as of the end of the last trace entry)
    private int numRows;

    private boolean hasPerformedNavigation;

    void setHasPerformedNavigation() {
        hasPerformedNavigation = true;
    }

    void incrementNumRows() {
        numRows++;
        hasPerformedNavigation = true;
    }

    void updateNumRows(int currentRow) {
        numRows = Math.max(numRows, currentRow);
        hasPerformedNavigation = true;
    }

    int getNumRows() {
        return numRows;
    }

    boolean hasPerformedNavigation() {
        return hasPerformedNavigation;
    }
}
