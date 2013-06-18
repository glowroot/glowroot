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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;

/**
 * For modeling an optional return value from a method when it is unknown whether that method
 * returns void or a value (value can be null).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class OptionalReturn {

    /**
     * Returns {@code true} if this instance represents a void return.
     * 
     * @return {@code true} if this instance represents a void return
     */
    public abstract boolean isVoid();

    /**
     * Returns the return value. Must check {@link #isVoid()} first.
     * 
     * @return the return value
     * @throws IllegalStateException
     *             if the return is void ({@link #isVoid} returns {@code true})
     */
    @Nullable
    public abstract Object getValue();

    /**
     * Returns an {@code OptionalReturn} instance representing a void return value.
     * 
     * @return an {@code OptionalReturn} instance representing a void return value
     */
    public static OptionalReturn fromVoid() {
        return VoidReturn.INSTANCE;
    }

    /**
     * Returns an {@code OptionalReturn} instance for the given (non-void) return value.
     * 
     * @param returnValue
     * @return an {@code OptionalReturn} instance for the given (non-void) return value
     */
    public static OptionalReturn fromValue(@Nullable Object returnValue) {
        return new NonVoidReturn(returnValue);
    }

    private static class VoidReturn extends OptionalReturn {
        private static final VoidReturn INSTANCE = new VoidReturn();
        @Override
        public Object getValue() {
            throw new IllegalStateException("Value is absent");
        }
        @Override
        public boolean isVoid() {
            return true;
        }
    }

    private static class NonVoidReturn extends OptionalReturn {
        @Nullable
        private final Object returnValue;
        public NonVoidReturn(@Nullable Object returnValue) {
            this.returnValue = returnValue;
        }
        @Override
        @Nullable
        public Object getValue() {
            return returnValue;
        }
        @Override
        public boolean isVoid() {
            return false;
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof NonVoidReturn) {
                NonVoidReturn that = (NonVoidReturn) o;
                return Objects.equal(returnValue, that.returnValue);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(returnValue);
        }
    }
}
