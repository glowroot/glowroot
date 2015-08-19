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
package org.glowroot.container.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

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

    private GaugeConfig(String display, String version) {
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("mbeanObjectName", mbeanObjectName)
                .add("mbeanAttributes", mbeanAttributes)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static GaugeConfig readValue(
            @JsonProperty("display") @Nullable String display,
            @JsonProperty("mbeanObjectName") @Nullable String mbeanObjectName,
            @JsonProperty("mbeanAttributes") @Nullable List<MBeanAttribute> mbeanAttributes,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(display, "display");
        checkRequiredProperty(mbeanObjectName, "mbeanObjectName");
        checkRequiredProperty(mbeanAttributes, "mbeanAttributes");
        checkRequiredProperty(version, "version");
        GaugeConfig config = new GaugeConfig(display, version);
        config.setMBeanObjectName(mbeanObjectName);
        config.setMBeanAttributes(mbeanAttributes);
        return config;
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

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("name", name)
                    .add("counter", counter)
                    .toString();
        }

        @JsonCreator
        static MBeanAttribute readValue(@JsonProperty("name") @Nullable String name,
                @JsonProperty("counter") @Nullable Boolean counter)
                        throws JsonMappingException {
            checkRequiredProperty(name, "name");
            checkRequiredProperty(counter, "counter");
            MBeanAttribute mbeanAttribute = new MBeanAttribute();
            mbeanAttribute.setName(name);
            mbeanAttribute.setCounter(counter);
            return mbeanAttribute;
        }
    }
}
