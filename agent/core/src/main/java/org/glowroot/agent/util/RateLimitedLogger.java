/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.util;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class RateLimitedLogger {

    private final Logger logger;

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);

    @GuardedBy("warningRateLimiter")
    private int countSinceLastWarning;

    public RateLimitedLogger(Class<?> clazz) {
        logger = LoggerFactory.getLogger(clazz);
    }

    public void warn(String format, /*@Nullable*/ Object... args) {
        synchronized (warningRateLimiter) {
            if (warningRateLimiter.tryAcquire(0, MILLISECONDS)) {
                if (countSinceLastWarning == 0) {
                    logger.warn(format + " (this warning will be logged at most once a minute)",
                            args);
                } else {
                    @Nullable
                    Object[] argsPlus = newArgsWithCountSinceLastWarning(args);
                    logger.warn(format + " (this warning will be logged at most once a minute, {}"
                            + " warnings were suppressed since it was last logged)", argsPlus);
                }
                countSinceLastWarning = 0;
            } else {
                countSinceLastWarning++;
            }
        }
    }

    @VisibleForTesting
    @Nullable
    Object[] newArgsWithCountSinceLastWarning(/*@Nullable*/ Object[] args) {
        if (args.length == 0) {
            return new Object[] {countSinceLastWarning};
        }
        @Nullable
        Object[] argsPlus = new Object[args.length + 1];
        if (args[args.length - 1] instanceof Throwable) {
            System.arraycopy(args, 0, argsPlus, 0, args.length - 1);
            argsPlus[args.length - 1] = countSinceLastWarning;
            argsPlus[args.length] = args[args.length - 1];
            return argsPlus;
        } else {
            System.arraycopy(args, 0, argsPlus, 0, args.length);
            argsPlus[args.length] = countSinceLastWarning;
            return argsPlus;
        }
    }
}
