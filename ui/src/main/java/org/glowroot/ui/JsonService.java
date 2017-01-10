/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.ui;

import java.lang.annotation.Retention;

import io.netty.handler.codec.http.HttpResponseStatus;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

// marker annotation
@interface JsonService {}

// similar to javax.ws.rs.GET
@Retention(RUNTIME)
@interface GET {
    String path();
    String permission();
}

// similar to javax.ws.rs.POST
@Retention(RUNTIME)
@interface POST {
    String path();
    String permission();
}

@Retention(RUNTIME)
@interface BindAgentId {}

@Retention(RUNTIME)
@interface BindAgentRollupId {}

@Retention(RUNTIME)
@interface BindRequest {}

@Retention(RUNTIME)
@interface BindAutoRefresh {}

@Retention(RUNTIME)
@interface BindAuthentication {}

// used for "expected" exceptions, these are not logged and their stack trace is not sent back to
// browser
@SuppressWarnings("serial")
class JsonServiceException extends RuntimeException {

    // storing (serializable) int instead of (non-serializable) HttpResponseStatus since Throwable
    // implements Serializable
    private final int statusCode;

    JsonServiceException(HttpResponseStatus status, Throwable cause) {
        super("", cause);
        this.statusCode = status.code();
    }

    JsonServiceException(HttpResponseStatus status, String message) {
        super(message);
        this.statusCode = status.code();
    }

    JsonServiceException(HttpResponseStatus status) {
        super();
        this.statusCode = status.code();
    }

    HttpResponseStatus getStatus() {
        return HttpResponseStatus.valueOf(statusCode);
    }
}
