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
package org.informantproject.core.weaving;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.objectweb.asm.Type;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class ParsedMethod {

    private final String name;
    private final Type[] args;

    static ParsedMethod from(String name, Type[] args) {
        return new ParsedMethod(name, args);
    }

    private ParsedMethod(String name, Type[] args) {
        this.name = name;
        this.args = args;
    }

    String getName() {
        return name;
    }

    Type[] getArgs() {
        return args;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof ParsedMethod)) {
            return false;
        }
        ParsedMethod other = (ParsedMethod) o;
        return Objects.equal(other.name, name) && Arrays.equals(other.args, args);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name, args);
    }
}

