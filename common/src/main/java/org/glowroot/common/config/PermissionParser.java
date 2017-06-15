/*
 * Copyright 2016-2017 the original author or authors.
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
import java.util.ListIterator;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

public class PermissionParser {

    private String permission;
    private int index;
    private boolean inAgentRollupId;
    private boolean inQuotedAgentRollupId;
    private final StringBuilder currAgentRollupId = new StringBuilder();
    private final List<String> agentRollupIds = Lists.newArrayList();

    public PermissionParser(String permission) {
        this.permission = permission;
    }

    public void parse() {
        if (permission.equals("agent")) {
            return;
        }
        if (permission.equals("agent:")) {
            permission = "agent";
            return;
        }
        if (!permission.startsWith("agent:")) {
            return;
        }
        index = "agent:".length();
        while (index < permission.length()) {
            readNextChar();
            if (!inAgentRollupId && permission.charAt(index - 1) == ':') {
                break;
            }
        }
        if (inAgentRollupId) {
            // add the last value
            agentRollupIds.add(currAgentRollupId.toString());
        }
        if (permission.length() == index) {
            permission = "agent";
        } else {
            permission = "agent:" + permission.substring(index);
        }
    }

    public List<String> getAgentRollupIds() {
        return agentRollupIds;
    }

    public String getPermission() {
        return permission;
    }

    private void readNextChar() {
        char c = permission.charAt(index++);
        if (isStartOfValue(c)) {
            startValue(c);
        } else if (isEndOfValue(c)) {
            endValue();
        } else if (inQuotedAgentRollupId && c == '\\') {
            currAgentRollupId.append(permission.charAt(index++));
        } else if (inAgentRollupId) {
            currAgentRollupId.append(c);
        }
    }

    private boolean isStartOfValue(char c) {
        return !inAgentRollupId && c != ',' && c != ':';
    }

    private void startValue(char c) {
        inAgentRollupId = true;
        if (c == '"') {
            inQuotedAgentRollupId = true;
        } else {
            currAgentRollupId.append(c);
        }
    }

    private boolean isEndOfValue(char c) {
        return isEndOfQuotedValue(c) || isEndOfNonQuotedValue(c);
    }

    private void endValue() {
        agentRollupIds.add(currAgentRollupId.toString());
        inAgentRollupId = false;
        inQuotedAgentRollupId = false;
        currAgentRollupId.setLength(0);
    }

    private boolean isEndOfQuotedValue(char c) {
        return inQuotedAgentRollupId && c == '"';
    }

    private boolean isEndOfNonQuotedValue(char c) {
        return inAgentRollupId && !inQuotedAgentRollupId && (c == ',' || c == ':');
    }

    public static String quoteIfNeededAndJoin(List<String> agentRollupIds) {
        List<String> quotedIfNeededAgentRollupIds = Lists.newArrayList();
        for (String agentRollupId : agentRollupIds) {
            if (agentRollupId.indexOf(',') != -1 || agentRollupId.indexOf(':') != -1) {
                quotedIfNeededAgentRollupIds.add(
                        "\"" + agentRollupId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            } else {
                quotedIfNeededAgentRollupIds.add(agentRollupId);
            }
        }
        return Joiner.on(',').join(quotedIfNeededAgentRollupIds);
    }

    public static boolean upgradeAgentPermissionsFrom_0_9_1_to_0_9_2(List<String> perms) {
        boolean hasAgentJvmAll = perms.contains("agent:tool");
        boolean upgrade = false;
        ListIterator<String> i = perms.listIterator();
        while (i.hasNext()) {
            String perm = i.next();
            if (perm.equals("agent:view")) {
                i.set("agent:transaction");
                i.add("agent:error");
                if (!hasAgentJvmAll) {
                    // in 0.9.1, agent:view gave access to JVM gauges and environment
                    i.add("agent:jvm:gauges");
                    i.add("agent:jvm:environment");
                }
                upgrade = true;
            } else if (perm.equals("agent:tool")) {
                i.set("agent:jvm");
                upgrade = true;
            } else if (perm.startsWith("agent:tool:")) {
                i.set("agent:jvm:" + perm.substring("agent:tool:".length()));
                upgrade = true;
            }
        }
        if (!upgrade) {
            return false;
        }
        // only apply below updates if upgrading from 0.9.1 to 0.9.2
        int configViewIndex = perms.indexOf("agent:config:view");
        int configEditIndex = perms.indexOf("agent:config:edit");
        if (configViewIndex != -1 && configEditIndex != -1) {
            perms.set(configViewIndex, "agent:config");
            perms.remove(configEditIndex);
        }
        return true;
    }
}
