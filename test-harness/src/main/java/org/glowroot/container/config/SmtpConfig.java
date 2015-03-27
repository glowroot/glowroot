/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.container.config;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class SmtpConfig {

    private @Nullable String fromEmailAddress;
    private @Nullable String fromDisplayName;
    private @Nullable String host;
    private @Nullable Integer port;
    private boolean ssl;
    private @Nullable String username;
    private boolean passwordExists;
    private Map<String, String> additionalProperties = Maps.newHashMap();

    // used for submitting a password change
    private String newPassword = "";

    // used for generating a test email
    private String testEmailRecipient = "";

    private final String version;

    public SmtpConfig(String version) {
        this.version = version;
    }

    public @Nullable String getFromEmailAddress() {
        return fromEmailAddress;
    }

    public void setFromEmailAddress(String fromEmailAddress) {
        this.fromEmailAddress = fromEmailAddress;
    }

    public @Nullable String getFromDisplayName() {
        return fromDisplayName;
    }

    public void setFromDisplayName(String fromDisplayName) {
        this.fromDisplayName = fromDisplayName;
    }

    public @Nullable String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public @Nullable Integer getPort() {
        return port;
    }

    public void setPort(@Nullable Integer port) {
        this.port = port;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public @Nullable String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isPasswordExists() {
        return passwordExists;
    }

    public void setPasswordExists(boolean passwordExists) {
        this.passwordExists = passwordExists;
    }

    public Map<String, String> getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(Map<String, String> additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public String getTestEmailRecipient() {
        return testEmailRecipient;
    }

    public void setTestEmailRecipient(String testEmailRecipient) {
        this.testEmailRecipient = testEmailRecipient;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof SmtpConfig) {
            SmtpConfig that = (SmtpConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(fromEmailAddress, that.fromEmailAddress)
                    && Objects.equal(fromDisplayName, that.fromDisplayName)
                    && Objects.equal(host, that.host)
                    && Objects.equal(port, that.port)
                    && Objects.equal(ssl, that.ssl)
                    && Objects.equal(username, that.username)
                    && Objects.equal(passwordExists, that.passwordExists)
                    && Objects.equal(additionalProperties, that.additionalProperties);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(fromEmailAddress, fromDisplayName, host, port, ssl, username,
                passwordExists, additionalProperties);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("fromEmailAddress", fromEmailAddress)
                .add("fromDisplayName", fromDisplayName)
                .add("host", host)
                .add("port", port)
                .add("ssl", ssl)
                .add("username", username)
                .add("passwordExists", passwordExists)
                .add("additionalProperties", additionalProperties)
                .add("newPassword", newPassword)
                .add("testEmailRecipient", testEmailRecipient)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static SmtpConfig readValue(
            @JsonProperty("fromEmailAddress") @Nullable String fromEmailAddress,
            @JsonProperty("fromDisplayName") @Nullable String fromDisplayName,
            @JsonProperty("host") @Nullable String host,
            @JsonProperty("port") @Nullable Integer port,
            @JsonProperty("ssl") @Nullable Boolean ssl,
            @JsonProperty("username") @Nullable String username,
            @JsonProperty("passwordExists") @Nullable Boolean passwordExists,
            @JsonProperty("additionalProperties") @Nullable Map<String, String> additionalProperties,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(fromEmailAddress, "fromEmailAddress");
        checkRequiredProperty(fromDisplayName, "fromDisplayName");
        checkRequiredProperty(host, "host");
        checkRequiredProperty(ssl, "ssl");
        checkRequiredProperty(username, "username");
        checkRequiredProperty(passwordExists, "passwordExists");
        checkRequiredProperty(additionalProperties, "additionalProperties");
        checkRequiredProperty(version, "version");
        SmtpConfig config = new SmtpConfig(version);
        config.setFromEmailAddress(fromEmailAddress);
        config.setFromDisplayName(fromDisplayName);
        config.setHost(host);
        config.setPort(port);
        config.setSsl(ssl);
        config.setUsername(username);
        config.setPasswordExists(passwordExists);
        config.setAdditionalProperties(additionalProperties);
        return config;
    }
}
