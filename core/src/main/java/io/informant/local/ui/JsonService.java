/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import java.lang.annotation.Retention;

import org.jboss.netty.handler.codec.http.HttpResponseStatus;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// marker annotation
@interface JsonService {}

// similar to javax.ws.rs.GET
@Retention(RUNTIME)
@interface GET {
    String value();
}

// similar to javax.ws.rs.POST
@Retention(RUNTIME)
@interface POST {
    String value();
}

@SuppressWarnings("serial")
class JsonServiceException extends Exception {
    private final HttpResponseStatus status;

    JsonServiceException(HttpResponseStatus status, Throwable cause) {
        super(cause);
        this.status = status;
    }

    HttpResponseStatus getStatus() {
        return status;
    }
}
