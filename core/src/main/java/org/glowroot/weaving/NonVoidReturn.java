/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.weaving;

import javax.annotation.Nullable;

import com.google.common.base.Objects;

import org.glowroot.api.OptionalReturn;
import org.glowroot.markers.UsedByGeneratedBytecode;

@UsedByGeneratedBytecode
public class NonVoidReturn implements OptionalReturn {

    @Nullable
    private final Object returnValue;

    public static OptionalReturn create(@Nullable Object returnValue) {
        return new NonVoidReturn(returnValue);
    }

    private NonVoidReturn(@Nullable Object returnValue) {
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
