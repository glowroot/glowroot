/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.agent.weaving.preinit;

import javax.annotation.Nullable;

import com.google.common.base.Objects;

public class ReferencedMethod implements Comparable<ReferencedMethod> {

    private final String owner;
    private final String name;
    private final String desc;

    // optimization, since this is used so heavily in HashSet
    private final int hashCode;

    public static ReferencedMethod create(String owner, String name, String desc) {
        return new ReferencedMethod(owner, name, desc);
    }

    public static ReferencedMethod create(String owner, String method) {
        String[] parts = method.split(":");
        return new ReferencedMethod(owner, parts[0], parts[1]);
    }

    private ReferencedMethod(String owner, String name, String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        hashCode = Objects.hashCode(owner, name, desc);
    }

    String getOwner() {
        return owner;
    }

    String getName() {
        return name;
    }

    String getDesc() {
        return desc;
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
                    && Objects.equal(desc, that.desc);
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
        return owner + ":" + name + ":" + desc;
    }
}
