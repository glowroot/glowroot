/*
 * Copyright 2019-2023 the original author or authors.
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
package org.glowroot.common.util;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Throwables {

    private static final Logger logger = LoggerFactory.getLogger(Throwables.class);

    private Throwables() {}

    public static String getBestMessage(Throwable t) {
        Throwable rootCause;
        try {
            rootCause = com.google.common.base.Throwables.getRootCause(t);
        } catch (IllegalArgumentException e) {
            // guava's Throwables throws IllegalArgumentException if there is a loop in the causal
            // chain (since guava 23.0)
            logger.warn(e.getMessage(), e);
            return t.toString();
        }
        // using Throwable.toString() to include the exception class name
        // because sometimes hard to know what message means without this context
        // e.g. java.net.UnknownHostException: google.com
        String message = rootCause.toString();
        if (Strings.isNullOrEmpty(message)) {
            // unlikely, but just in case
            return rootCause.getClass().getName();
        } else {
            return message;
        }
    }
}
