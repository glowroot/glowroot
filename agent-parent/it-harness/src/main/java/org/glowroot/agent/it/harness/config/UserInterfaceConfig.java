/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.it.harness.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

public class UserInterfaceConfig {

    private String defaultDisplayedTransactionType = "";
    private List<Double> defaultDisplayedPercentiles = Lists.newArrayList();
    private int port;
    private boolean adminPasswordEnabled;
    private boolean readOnlyPasswordEnabled;
    private @Nullable AnonymousAccess anonymousAccess;
    private int sessionTimeoutMinutes;

    // used for submitting a password change
    private String currentAdminPassword = "";
    // used for submitting a password change
    private String newAdminPassword = "";
    // used for submitting a password change
    private String newReadOnlyPassword = "";

    private final String version;

    @JsonCreator
    private UserInterfaceConfig(@JsonProperty("version") String version) {
        this.version = version;
    }

    public String getDefaultDisplayedTransactionType() {
        return defaultDisplayedTransactionType;
    }

    public void setDefaultDisplayedTransactionType(String defaultDisplayedTransactionType) {
        this.defaultDisplayedTransactionType = defaultDisplayedTransactionType;
    }

    public List<Double> getDefaultDisplayedPercentiles() {
        return defaultDisplayedPercentiles;
    }

    public void setDefaultDisplayedPercentiles(List<Double> defaultDisplayedPercentiles) {
        this.defaultDisplayedPercentiles = defaultDisplayedPercentiles;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAdminPasswordEnabled(boolean adminPasswordEnabled) {
        this.adminPasswordEnabled = adminPasswordEnabled;
    }

    public void setReadOnlyPasswordEnabled(boolean readOnlyPasswordEnabled) {
        this.readOnlyPasswordEnabled = readOnlyPasswordEnabled;
    }

    public @Nullable AnonymousAccess getAnonymousAccess() {
        return anonymousAccess;
    }

    public void setAnonymousAccess(AnonymousAccess anonymousAccess) {
        this.anonymousAccess = anonymousAccess;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public boolean isAdminPasswordEnabled() {
        return adminPasswordEnabled;
    }

    public boolean isReadOnlyPasswordEnabled() {
        return readOnlyPasswordEnabled;
    }

    public @Nullable String getCurrentAdminPassword() {
        return currentAdminPassword;
    }

    public void setCurrentAdminPassword(String currentAdminPassword) {
        this.currentAdminPassword = currentAdminPassword;
    }

    public @Nullable String getNewAdminPassword() {
        return newAdminPassword;
    }

    public void setNewAdminPassword(String newAdminPassword) {
        this.newAdminPassword = newAdminPassword;
    }

    public @Nullable String getNewReadOnlyPassword() {
        return newReadOnlyPassword;
    }

    public void setNewReadOnlyPassword(String newReadOnlyPassword) {
        this.newReadOnlyPassword = newReadOnlyPassword;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserInterfaceConfig) {
            UserInterfaceConfig that = (UserInterfaceConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            //
            // also intentionally leaving off currentAdminPassword, newAdminPassword and
            // newReadOnlyPassword since those are just used as a temporary messaging mechanism
            return Objects.equal(defaultDisplayedTransactionType,
                    that.defaultDisplayedTransactionType)
                    && Objects.equal(defaultDisplayedPercentiles, that.defaultDisplayedPercentiles)
                    && Objects.equal(port, that.port)
                    && Objects.equal(adminPasswordEnabled, that.adminPasswordEnabled)
                    && Objects.equal(readOnlyPasswordEnabled, that.readOnlyPasswordEnabled)
                    && Objects.equal(anonymousAccess, that.anonymousAccess)
                    && Objects.equal(sessionTimeoutMinutes, that.sessionTimeoutMinutes);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        //
        // also intentionally leaving off currentPassword and newPassword since those are just used
        // as a temporary messaging mechanism
        return Objects.hashCode(defaultDisplayedTransactionType, defaultDisplayedPercentiles, port,
                adminPasswordEnabled, readOnlyPasswordEnabled, anonymousAccess,
                sessionTimeoutMinutes);
    }

    public enum AnonymousAccess {
        NONE, READ_ONLY, ADMIN
    }
}
