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
package org.glowroot.config;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.common.Marshaling2;
import org.glowroot.config.MarshalingRoutines.LowercaseMarshaling;

@Value.Immutable
@Json.Marshaled
@Json.Import(MarshalingRoutines.class)
public class UserInterfaceConfig {

    @Value.Default
    public int port() {
        return 4000;
    }

    @Value.Default
    public String adminPasswordHash() {
        return "";
    }

    @Value.Default
    public String readOnlyPasswordHash() {
        return "";
    }

    @Value.Default
    public AnonymousAccess anonymousAccess() {
        return AnonymousAccess.ADMIN;
    }

    // timeout 0 means sessions do not time out (except on jvm restart)
    @Value.Default
    public int sessionTimeoutMinutes() {
        return 30;
    }

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling2.toJson(this), Charsets.UTF_8).toString();
    }

    public boolean adminPasswordEnabled() {
        return !adminPasswordHash().isEmpty();
    }

    public boolean readOnlyPasswordEnabled() {
        return !readOnlyPasswordHash().isEmpty();
    }

    public static enum AnonymousAccess implements LowercaseMarshaling {
        NONE, READ_ONLY, ADMIN
    }
}
