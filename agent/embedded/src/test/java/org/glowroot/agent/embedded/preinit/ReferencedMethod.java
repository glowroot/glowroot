/*
 * Copyright 2012-2018 the original author or authors.
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
package org.glowroot.agent.embedded.preinit;

import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ReferencedMethod implements Comparable<ReferencedMethod> {

    private final String owner;
    private final String name;
    private final String descriptor;

    // optimization, since this is used so heavily in HashSet
    private final int hashCode;

    public static ReferencedMethod create(String owner, String name, String descriptor) {
        return new ReferencedMethod(owner, name, descriptor);
    }

    public static ReferencedMethod create(String owner, String method) {
        String[] parts = method.split(":");
        return new ReferencedMethod(owner, parts[0], parts[1]);
    }

    private ReferencedMethod(String owner, String name, String descriptor) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        hashCode = Objects.hashCode(owner, name, descriptor);
    }

    String getOwner() {
        return owner;
    }

    String getName() {
        return name;
    }

    String getDesc() {
        return descriptor;
    }

    @Override
    public int compareTo(ReferencedMethod o) {
        return toString().compareTo(o.toString());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof ReferencedMethod) {
            ReferencedMethod that = (ReferencedMethod) obj;
            return Objects.equal(owner, that.owner) && Objects.equal(name, that.name)
                    && Objects.equal(descriptor, that.descriptor);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // cached since it is used so heavily
        return hashCode;
    }

    @Override
    public String toString() {
        return owner + ":" + name + ":" + descriptor;
    }
}
