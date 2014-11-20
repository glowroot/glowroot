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
package org.glowroot.local.store;

import java.sql.SQLException;

import javax.annotation.Nullable;

import org.glowroot.collector.Existence;
import org.glowroot.local.store.FileBlock.InvalidBlockIdFormatException;

class RowMappers {

    private RowMappers() {}

    static Existence getExistence(@Nullable String fileBlockId, CappedDatabase cappedDatabase)
            throws SQLException {
        if (fileBlockId == null) {
            return Existence.NO;
        }
        FileBlock fileBlock;
        try {
            fileBlock = FileBlock.from(fileBlockId);
        } catch (InvalidBlockIdFormatException e) {
            throw new SQLException(e);
        }
        if (cappedDatabase.isExpired(fileBlock)) {
            return Existence.EXPIRED;
        } else {
            return Existence.YES;
        }
    }
}
