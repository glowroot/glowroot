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
package org.glowroot.agent.model;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.QueryEntry;

// ideally this would be a component of it's subclasses instead of a parent class, but for
// micro-optimization purposes it is not (in order to prevent extra object instances/memory
// addresses that must be navigated)
public abstract class QueryEntryBase implements QueryEntry {

    private final @Nullable QueryData queryData;

    // row numbers start at 1
    private long currRow = -1;

    private long maxRow;

    protected QueryEntryBase(@Nullable QueryData queryData) {
        this.queryData = queryData;
    }

    public void extendQueryData(long startTick) {
        if (queryData != null) {
            queryData.extend(startTick);
        }
    }

    public void endQueryData(long endTick) {
        if (queryData != null) {
            queryData.end(endTick);
        }
    }

    @Override
    public void rowNavigationAttempted() {
        if (currRow == -1) {
            currRow = 0;
            if (queryData != null) {
                // queryData can be null here if the aggregated query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.setHasTotalRows();
            }
        }
    }

    @Override
    public void incrementCurrRow() {
        if (currRow == -1) {
            currRow = 1;
            maxRow = 1;
            if (queryData != null) {
                // queryData can be null here if the aggregated query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(1);
            }
        } else if (currRow == maxRow) {
            currRow++;
            maxRow = currRow;
            if (queryData != null) {
                // queryData can be null here if the query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(1);
            }
        } else {
            currRow++;
        }
    }

    @Override
    public void setCurrRow(long row) {
        if (row > maxRow) {
            if (queryData != null) {
                // queryData can be null here if the aggregated query limit is exceeded
                // (though typically query limit is larger than trace entry limit)
                queryData.incrementRowCount(row - maxRow);
            }
            maxRow = row;
        }
        currRow = row;
    }

    // row count -1 means no navigation has been attempted
    // row count 0 means that navigation has been attempted but there were 0 rows
    protected boolean isRowNavigationAttempted() {
        return currRow != -1;
    }

    protected long getRowCount() {
        return maxRow;
    }

    protected @Nullable QueryData getQueryData() {
        return queryData;
    }

    protected @Nullable String getQueryText() {
        return queryData == null ? null : queryData.getQueryText();
    }
}
