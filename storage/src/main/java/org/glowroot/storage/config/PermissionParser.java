/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.storage.config;

import java.util.List;
import java.util.ListIterator;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

public class PermissionParser {

    private String permission;
    private int index;
    private boolean inAgentId;
    private boolean inQuotedAgentId;
    private final StringBuilder currAgentId = new StringBuilder();
    private final List<String> agentIds = Lists.newArrayList();

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
            if (!inAgentId && permission.charAt(index - 1) == ':') {
                break;
            }
        }
        if (inAgentId) {
            // add the last value
            agentIds.add(currAgentId.toString());
        }
        if (permission.length() == index) {
            permission = "agent";
        } else {
            permission = "agent:" + permission.substring(index);
        }
    }

    public List<String> getAgentIds() {
        return agentIds;
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
        } else if (inQuotedAgentId && c == '\\') {
            currAgentId.append(permission.charAt(index++));
        } else if (inAgentId) {
            currAgentId.append(c);
        }
    }

    private boolean isStartOfValue(char c) {
        return !inAgentId && c != ',' && c != ':';
    }

    private void startValue(char c) {
        inAgentId = true;
        if (c == '"') {
            inQuotedAgentId = true;
        } else {
            currAgentId.append(c);
        }
    }

    private boolean isEndOfValue(char c) {
        return isEndOfQuotedValue(c) || isEndOfNonQuotedValue(c);
    }

    private void endValue() {
        agentIds.add(currAgentId.toString());
        inAgentId = false;
        inQuotedAgentId = false;
        currAgentId.setLength(0);
    }

    private boolean isEndOfQuotedValue(char c) {
        return inQuotedAgentId && c == '"';
    }

    private boolean isEndOfNonQuotedValue(char c) {
        return inAgentId && !inQuotedAgentId && (c == ',' || c == ':');
    }

    public static String quoteIfNecessaryAndJoin(List<String> agentIds) {
        List<String> quotedIfNecessaryAgentIds = Lists.newArrayList();
        for (String agentId : agentIds) {
            if (agentId.indexOf(',') != -1 || agentId.indexOf(':') != -1) {
                quotedIfNecessaryAgentIds
                        .add("\"" + agentId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            } else {
                quotedIfNecessaryAgentIds.add(agentId);
            }
        }
        return Joiner.on(',').join(quotedIfNecessaryAgentIds);
    }

    public static boolean upgradeAgentPermissions(List<String> perms) {
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
