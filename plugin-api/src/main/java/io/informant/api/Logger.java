/**
 * Copyright 2011-2013 the original author or authors.
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

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

/**
 * This is simply a wrapper of the SLF4J Logger API without the Marker support.
 * 
 * By using this wrapper, Informant is able to use either shaded or unshaded slf4j binding, as well
 * as log messages to its embedded H2 database so the log messages can be displayed via the user
 * interface.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public interface Logger {

    boolean isTraceEnabled();
    void trace(String msg);
    void trace(String format, @Nullable Object arg);
    void trace(String format, @Nullable Object arg1, @Nullable Object arg2);
    void trace(String format, @Nullable Object... argArray);
    void trace(@Nullable String msg, Throwable t);

    boolean isDebugEnabled();
    void debug(String msg);
    void debug(String format, @Nullable Object arg);
    void debug(String format, @Nullable Object arg1, @Nullable Object arg2);
    void debug(String format, @Nullable Object... argArray);
    void debug(@Nullable String msg, Throwable t);

    boolean isInfoEnabled();
    void info(String msg);
    void info(String format, @Nullable Object arg);
    void info(String format, @Nullable Object arg1, @Nullable Object arg2);
    void info(String format, @Nullable Object... argArray);
    void info(@Nullable String msg, Throwable t);

    boolean isWarnEnabled();
    void warn(String msg);
    void warn(String format, @Nullable Object arg);
    void warn(String format, @Nullable Object arg1, @Nullable Object arg2);
    void warn(String format, @Nullable Object... argArray);
    void warn(@Nullable String msg, Throwable t);

    boolean isErrorEnabled();
    void error(String msg);
    void error(String format, @Nullable Object arg);
    void error(String format, @Nullable Object arg1, @Nullable Object arg2);
    void error(String format, @Nullable Object... argArray);
    void error(@Nullable String msg, Throwable t);
}
