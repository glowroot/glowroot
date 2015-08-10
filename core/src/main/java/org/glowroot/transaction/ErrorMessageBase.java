/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.transaction;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import org.immutables.value.Value;

import org.glowroot.common.Styles;

@Value.Immutable
@Styles.AllParameters
public abstract class ErrorMessageBase {

    public abstract String message();
    public abstract @Nullable ThrowableInfo throwable();

    public static ErrorMessage from(Throwable t) {
        return from(null, t);
    }

    // accepts null message so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message) {
        return from(message, null);
    }

    // accepts null values so callers don't have to check if passing it in from elsewhere
    public static ErrorMessage from(@Nullable String message, @Nullable Throwable t) {
        String msg = Strings.nullToEmpty(message);
        if (t == null) {
            return ErrorMessage.of(msg, null);
        }
        if (msg.isEmpty()) {
            msg = Strings.nullToEmpty(t.getMessage());
        }
        return ErrorMessage.of(msg, ThrowableInfoBase.from(t));
    }
}
