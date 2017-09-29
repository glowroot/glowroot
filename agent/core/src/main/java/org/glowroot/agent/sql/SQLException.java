/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.sql;

import java.util.Iterator;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

@SuppressWarnings("serial")
public class SQLException extends Exception implements Iterable<Throwable> {

    private final @Nullable String sqlState;

    private final int errorCode;

    private volatile @Nullable SQLException next;

    public SQLException(String message, @Nullable String sqlState, int errorCode) {
        super(message);
        this.sqlState = sqlState;
        this.errorCode = errorCode;
    }

    public SQLException(String message, @Nullable String sqlState) {
        super(message);
        this.sqlState = sqlState;
        this.errorCode = 0;
    }

    public SQLException(String message) {
        super(message);
        this.sqlState = null;
        this.errorCode = 0;
    }

    public SQLException() {
        super();
        this.sqlState = null;
        this.errorCode = 0;
    }

    public SQLException(@Nullable Throwable cause) {
        super(cause);
        this.sqlState = null;
        this.errorCode = 0;
    }

    public SQLException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.sqlState = null;
        this.errorCode = 0;
    }

    public SQLException(String message, @Nullable String sqlState, @Nullable Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.errorCode = 0;
    }

    public SQLException(String message, @Nullable String sqlState, int errorCode,
            @Nullable Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.errorCode = errorCode;
    }

    public @Nullable String getSQLState() {
        return sqlState;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public @Nullable SQLException getNextException() {
        return next;
    }

    // no need to worry about multi-threaded race condition for glowroot's H2 usage
    public void setNextException(SQLException exception) {
        SQLException curr = this;
        while (true) {
            SQLException next = curr.next;
            if (next == null) {
                curr.next = exception;
                return;
            }
            curr = next;
        }
    }

    @Override
    public Iterator<Throwable> iterator() {
        return new SQLExceptionIterator(this);
    }

    private static class SQLExceptionIterator implements Iterator<Throwable> {

        private @Nullable Throwable nextCause; // if non-null return this instead
        private @Nullable SQLException nextSqlException;

        private SQLExceptionIterator(SQLException head) {
            nextCause = null;
            nextSqlException = head;
        }

        @Override
        public boolean hasNext() {
            return nextCause != null || nextSqlException != null;
        }

        @Override
        public Throwable next() {
            Throwable currCause = nextCause;
            if (currCause != null) {
                nextCause = null;
                return currCause;
            }
            SQLException currSqlException = nextSqlException;
            if (currSqlException == null) {
                throw new NoSuchElementException();
            }
            nextCause = currSqlException.getCause();
            nextSqlException = currSqlException.getNextException();
            return currSqlException;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
