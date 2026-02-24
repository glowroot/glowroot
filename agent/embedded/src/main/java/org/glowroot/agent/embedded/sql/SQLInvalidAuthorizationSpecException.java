/*
 * Copyright 2024-2025 the original author or authors.
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
package org.glowroot.agent.embedded.sql;

// this class serves as the relocation target for java.sql.SQLInvalidAuthorizationSpecException
// so that H2's JdbcSQLInvalidAuthorizationSpecException is assignable to
// org.glowroot.agent.embedded.sql.SQLException after shading
@SuppressWarnings("serial")
public class SQLInvalidAuthorizationSpecException extends SQLNonTransientException {

    public SQLInvalidAuthorizationSpecException(String reason, String sqlState, int vendorCode) {
        super(reason, sqlState, vendorCode);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String sqlState) {
        super(reason, sqlState);
    }

    public SQLInvalidAuthorizationSpecException(String reason) {
        super(reason);
    }

    public SQLInvalidAuthorizationSpecException() {
        super();
    }

    public SQLInvalidAuthorizationSpecException(Throwable cause) {
        super(cause);
    }

    public SQLInvalidAuthorizationSpecException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public SQLInvalidAuthorizationSpecException(String reason, String sqlState, Throwable cause) {
        super(reason, sqlState, cause);
    }
}
