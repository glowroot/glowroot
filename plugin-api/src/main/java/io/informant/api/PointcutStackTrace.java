/**
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
package io.informant.api;

import com.google.common.base.Joiner;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@SuppressWarnings("serial")
public class PointcutStackTrace extends RuntimeException {

    private static final Logger logger = LoggerFactory.getLogger(PointcutStackTrace.class);

    private final Class<?> pointcutType;

    public PointcutStackTrace(Class<?> pointcutType) {
        this.pointcutType = pointcutType;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        StackTraceElement[] elements = super.getStackTrace();
        int i;
        boolean inPointcut = false;
        for (i = 0; i < elements.length; i++) {
            if (elements[i].getClassName().equals(pointcutType.getName())) {
                inPointcut = true;
            } else if (inPointcut && !elements[i].getClassName().equals(pointcutType.getName())) {
                break;
            }
        }
        if (i == elements.length) {
            logger.warn("could not find pointcut '{}' in stack trace:\n  {}",
                    pointcutType.getName(), Joiner.on("\n  ").join(elements));
            return elements;
        } else {
            // strip off any bytecode generated $informant$ methods from the top (others will be
            // stripped later when the span stack trace is stored)
            while (elements[i].getMethodName().contains("$informant$")) {
                i++;
            }
            // increment i once more to strip one element since all pointcuts are on method
            // execution, and the stack trace will either be at method entry (if captured @OnBefore)
            // or at method exit (if captured @OnReturn/@OnThrow/@OnAfter), which doesn't provide
            // any more info than the call point one level up in the stack
            i++;
            StackTraceElement[] trimmedElements = new StackTraceElement[elements.length - i];
            System.arraycopy(elements, i, trimmedElements, 0, trimmedElements.length);
            return trimmedElements;
        }
    }
}
