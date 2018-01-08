/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.common.config;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class RoleConfig {

    public abstract String name();
    public abstract ImmutableSet<String> permissions();

    @JsonIgnore
    @Value.Default
    public boolean central() {
        return false;
    }

    @Value.Derived
    @JsonIgnore
    public ImmutableSet<SimplePermission> simplePermissions() {
        Set<SimplePermission> simplePermissions = Sets.newHashSet();
        for (String permission : permissions()) {
            if (central()) {
                simplePermissions.add(SimplePermission.create(permission));
            } else {
                simplePermissions.add(SimplePermission.create("", permission));
            }
        }
        return ImmutableSet.copyOf(simplePermissions);
    }

    public boolean isPermitted(SimplePermission permission) {
        for (SimplePermission simplePermission : simplePermissions()) {
            if (simplePermission.implies(permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPermittedForSomeAgentRollup(List<String> permissionParts) {
        for (SimplePermission simplePermission : simplePermissions()) {
            if (SimplePermission.implies(simplePermission.parts(), permissionParts)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyPermissionImpliedBy(List<String> permissionParts) {
        for (SimplePermission simplePermission : simplePermissions()) {
            if (SimplePermission.implies(permissionParts, simplePermission.parts())) {
                return true;
            }
        }
        return false;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }

    @Value.Immutable
    public abstract static class SimplePermission {

        public static SimplePermission create(String permission) {
            PermissionParser parser = new PermissionParser(permission);
            parser.parse();
            return ImmutableSimplePermission.builder()
                    .addAllAgentIds(parser.getAgentRollupIds())
                    .addAllParts(Splitter.on(':').splitToList(parser.getPermission()))
                    .build();
        }

        public static SimplePermission create(String agentId, String permission) {
            return ImmutableSimplePermission.builder()
                    .addAgentIds(agentId)
                    .addAllParts(Splitter.on(':').splitToList(permission))
                    .build();
        }

        public abstract List<String> agentIds();
        public abstract List<String> parts();

        boolean implies(SimplePermission other) {
            if (!agentIds().contains("*") && !agentListImplies(agentIds(), other.agentIds())) {
                return false;
            }
            List<String> otherParts = other.parts();
            return implies(parts(), otherParts);
        }

        private static boolean agentListImplies(List<String> agentIds, List<String> otherAgentIds) {
            for (String otherAgentId : otherAgentIds) {
                if (!agentListImplies(agentIds, otherAgentId)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean agentListImplies(List<String> agentIds, String otherAgentId) {
            for (String agentId : agentIds) {
                if (agentIds.contains(otherAgentId)) {
                    return true;
                }
                if (agentId.endsWith("::") && otherAgentId.startsWith(agentId)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean implies(List<String> parts, List<String> otherParts) {
            if (otherParts.size() < parts.size()) {
                return false;
            }
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                String otherPart = otherParts.get(i);
                if (!implies(part, otherPart)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean implies(String part, String otherPart) {
            return part.equals(otherPart) || part.equals("*");
        }
    }
}
