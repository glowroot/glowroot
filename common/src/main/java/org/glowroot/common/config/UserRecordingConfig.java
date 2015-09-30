/*
 * Copyright 2012-2015 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

@Value.Immutable
public abstract class UserRecordingConfig {

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public boolean enabled() {
        return false;
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String user() {
        return "";
    }

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public int profileIntervalMillis() {
        // default is currently 0 so that default configuration won't get written back to
        // config.json, if this feature becomes "public" then change the default back to 10
        return 0;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
