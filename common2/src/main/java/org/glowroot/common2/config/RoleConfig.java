/*
 * Copyright 2016-2023 the original author or authors.
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
package org.glowroot.common2.config;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;
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

    public boolean hasAnyPermissionImpliedBy(List<String> permissionParts) {
        for (SimplePermission simplePermission : simplePermissions()) {
            if (SimplePermission.implies(permissionParts, simplePermission.parts())) {
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

    public HasAnyPermission hasAnyPermissionForAgentRollup(String agentRollupId) {
        boolean onlyInChild = false;
        for (SimplePermission permission : simplePermissions()) {
            HasAnyPermission hasAnyPermission =
                    permission.hasAnyPermissionForAgentRollup(agentRollupId);
            if (hasAnyPermission == HasAnyPermission.YES) {
                return HasAnyPermission.YES;
            } else if (hasAnyPermission == HasAnyPermission.ONLY_IN_CHILD) {
                onlyInChild = true;
            }
        }
        return onlyInChild ? HasAnyPermission.ONLY_IN_CHILD : HasAnyPermission.NO;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }

    public static enum HasAnyPermission {
        YES, NO, ONLY_IN_CHILD // "only in child" means that this rollup needs to be visible in menu
    }

    @Value.Immutable
    public abstract static class SimplePermission {

        public static SimplePermission create(String permission) {
            PermissionParser parser = new PermissionParser(permission);
            parser.parse();
            return ImmutableSimplePermission.builder()
                    .addAllAgentRollupIds(parser.getAgentRollupIds())
                    .addAllParts(Splitter.on(':').splitToList(parser.getPermission()))
                    .build();
        }

        public static SimplePermission create(String agentId, String permission) {
            return ImmutableSimplePermission.builder()
                    .addAgentRollupIds(agentId)
                    .addAllParts(Splitter.on(':').splitToList(permission))
                    .build();
        }

        public abstract List<String> agentRollupIds();
        public abstract List<String> parts();

        @VisibleForTesting
        boolean implies(SimplePermission other) {
            if (!agentListImplies(other.agentRollupIds())) {
                return false;
            }
            List<String> otherParts = other.parts();
            return implies(parts(), otherParts);
        }

        private boolean agentListImplies(List<String> otherAgentRollupIds) {
            for (String otherAgentRollupId : otherAgentRollupIds) {
                if (!agentListImplies(otherAgentRollupId)) {
                    return false;
                }
            }
            return true;
        }

        private boolean agentListImplies(String otherAgentRollupId) {
            for (String agentRollupId : agentRollupIds()) {
                if (agentRollupId.equals("*") || agentRollupIds().contains(otherAgentRollupId)) {
                    return true;
                }
                if (agentRollupId.endsWith("::") && otherAgentRollupId.startsWith(agentRollupId)) {
                    return true;
                }
            }
            return false;
        }

        private HasAnyPermission hasAnyPermissionForAgentRollup(String otherAgentRollupId) {
            boolean onlyInChild = false;
            for (String agentRollupId : agentRollupIds()) {
                if (agentRollupId.equals("*") || agentRollupIds().contains(otherAgentRollupId)) {
                    return HasAnyPermission.YES;
                }
                if (agentRollupId.endsWith("::") && otherAgentRollupId.startsWith(agentRollupId)) {
                    onlyInChild = true;
                }
            }
            return onlyInChild ? HasAnyPermission.ONLY_IN_CHILD : HasAnyPermission.NO;
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
