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

public class GaugeConfig {

    // display is read-only (derived) attribute
    private @Nullable String display;
    private @Nullable String mbeanObjectName;
    private List<MBeanAttribute> mbeanAttributes = Lists.newArrayList();

    // null for new gauge config records that haven't been sent to server yet
    private @Nullable final String version;

    // used to create new gauge config records that haven't been sent to server yet
    public GaugeConfig() {
        version = null;
    }

    @JsonCreator
    private GaugeConfig(@JsonProperty("display") String display,
            @JsonProperty("version") String version) {
        this.display = display;
        this.version = version;
    }

    public @Nullable String getDisplay() {
        return display;
    }

    public @Nullable String getMBeanObjectName() {
        return mbeanObjectName;
    }

    public void setMBeanObjectName(String mbeanObjectName) {
        this.mbeanObjectName = mbeanObjectName;
    }

    public List<MBeanAttribute> getMBeanAttributes() {
        return mbeanAttributes;
    }

    public void setMBeanAttributes(List<MBeanAttribute> mbeanAttributes) {
        this.mbeanAttributes = mbeanAttributes;
    }

    public @Nullable String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof GaugeConfig) {
            GaugeConfig that = (GaugeConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(mbeanObjectName, that.mbeanObjectName)
                    && Objects.equal(mbeanAttributes, that.mbeanAttributes);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(mbeanObjectName, mbeanAttributes);
    }

    public static class MBeanAttribute {

        private @Nullable String name;
        private boolean counter;

        public @Nullable String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isCounter() {
            return counter;
        }

        public void setCounter(boolean counter) {
            this.counter = counter;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof MBeanAttribute) {
                MBeanAttribute that = (MBeanAttribute) obj;
                return Objects.equal(name, that.name)
                        && Objects.equal(counter, that.counter);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(name, counter);
        }
    }
}
