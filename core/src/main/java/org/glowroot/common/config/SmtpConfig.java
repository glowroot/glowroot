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
package org.glowroot.common.config;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.immutables.value.Value;

@Value.Immutable
public abstract class SmtpConfig {

    @Value.Default
    public String fromEmailAddress() {
        return "";
    }
    @Value.Default
    public String fromDisplayName() {
        return "";
    }
    @Value.Default
    public String host() {
        return "";
    }
    public abstract @Nullable Integer port();
    @Value.Default
    public boolean ssl() {
        return false;
    }
    @Value.Default
    public String username() {
        return "";
    }
    @Value.Default
    public String encryptedPassword() {
        return "";
    }
    public abstract Map<String, String> additionalProperties();

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
