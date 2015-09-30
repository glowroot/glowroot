/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, @Nullable Version 2.0 (@Nullable the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, @Nullable software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, @Nullable either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.api;

import javax.annotation.Nullable;

/**
 * Very thin wrapper around SLF4J so plugins don't have to worry about SLF4J shading.
 */
public interface Logger {

    String getName();

    boolean isTraceEnabled();

    void trace(@Nullable String msg);

    void trace(@Nullable String format, @Nullable Object arg);

    void trace(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2);

    void trace(@Nullable String format, @Nullable Object... arguments);

    void trace(@Nullable String msg, @Nullable Throwable t);

    boolean isDebugEnabled();

    void debug(@Nullable String msg);

    void debug(@Nullable String format, @Nullable Object arg);

    void debug(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2);

    void debug(@Nullable String format, @Nullable Object... arguments);

    void debug(@Nullable String msg, @Nullable Throwable t);

    boolean isInfoEnabled();

    void info(@Nullable String msg);

    void info(@Nullable String format, @Nullable Object arg);

    void info(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2);

    void info(@Nullable String format, @Nullable Object... arguments);

    void info(@Nullable String msg, @Nullable Throwable t);

    boolean isWarnEnabled();

    void warn(@Nullable String msg);

    void warn(@Nullable String format, @Nullable Object arg);

    void warn(@Nullable String format, @Nullable Object... arguments);

    void warn(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2);

    void warn(@Nullable String msg, @Nullable Throwable t);

    boolean isErrorEnabled();

    void error(@Nullable String msg);

    void error(@Nullable String format, @Nullable Object arg);

    void error(@Nullable String format, @Nullable Object arg1, @Nullable Object arg2);

    void error(@Nullable String format, @Nullable Object... arguments);

    void error(@Nullable String msg, @Nullable Throwable t);
}
