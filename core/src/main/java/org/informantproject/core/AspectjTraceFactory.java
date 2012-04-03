/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core;

import org.informantproject.shaded.aspectj.weaver.tools.AbstractTrace;
import org.informantproject.shaded.aspectj.weaver.tools.Trace;
import org.informantproject.shaded.aspectj.weaver.tools.TraceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AspectjTraceFactory extends TraceFactory {

    @Override
    public Trace getTrace(@SuppressWarnings("rawtypes") Class clazz) {
        return new Slf4jTrace(clazz);
    }

    // similar to AspectJ's CommonsTrace
    public class Slf4jTrace extends AbstractTrace {

        private final Logger log;
        private final String className;

        public Slf4jTrace(Class<?> clazz) {
            super(clazz);
            this.log = LoggerFactory.getLogger(clazz);
            this.className = tracedClass.getName();
        }

        @Override
        public void enter(String methodName, Object thiz, Object[] args) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage(">", className, methodName, thiz, args));
            }
        }

        @Override
        public void enter(String methodName, Object thiz) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage(">", className, methodName, thiz, null));
            }
        }

        @Override
        public void exit(String methodName, Object ret) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage("<", className, methodName, ret, null));
            }
        }

        @Override
        public void exit(String methodName, Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage("<", className, methodName, t, null));
            }
        }

        public void exit(String methodName) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage("<", className, methodName, null, null));
            }
        }

        public void event(String methodName, Object thiz, Object[] args) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage("-", className, methodName, thiz, args));
            }
        }

        public void event(String methodName) {
            if (log.isDebugEnabled()) {
                log.debug(formatMessage("-", className, methodName, null, null));
            }
        }

        public boolean isTraceEnabled() {
            return log.isDebugEnabled();
        }

        public void setTraceEnabled(boolean enabled) {}

        public void debug(String message) {
            if (log.isDebugEnabled()) {
                log.debug(message);
            }
        }

        public void info(String message) {
            if (log.isInfoEnabled()) {
                log.info(message);
            }
        }

        public void warn(String message, Throwable t) {
            if (log.isWarnEnabled()) {
                log.warn(message, t);
            }
        }

        public void error(String message, Throwable t) {
            if (log.isErrorEnabled()) {
                log.error(message, t);
            }
        }

        public void fatal(String message, Throwable t) {
            if (log.isErrorEnabled()) {
                log.error(message, t);
            }
        }
    }
}
