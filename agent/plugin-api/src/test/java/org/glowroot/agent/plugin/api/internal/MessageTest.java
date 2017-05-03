/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.plugin.api.internal;

import java.util.List;
import java.util.Map;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.junit.Test;

import org.glowroot.agent.plugin.api.Message;

import static org.assertj.core.api.Assertions.assertThat;

public class MessageTest {

    @Test
    public void shouldFormatConstant() {
        ReadableMessage message = (ReadableMessage) Message.create("constant");
        assertThat(message.getText()).isEqualTo("constant");
    }

    @Test
    public void shouldFormatSingle() {
        ReadableMessage message = (ReadableMessage) Message.create("{}", "test");
        assertThat(message.getText()).isEqualTo("test");
    }

    @Test
    public void shouldFormatSinglePlus() {
        ReadableMessage message = (ReadableMessage) Message.create("one {} two", "test");
        assertThat(message.getText()).isEqualTo("one test two");
    }

    @Test
    public void shouldFormatMultiple() {
        ReadableMessage message =
                (ReadableMessage) Message.create("one {} two {}{}", "test", "2", "3");
        assertThat(message.getText()).isEqualTo("one test two 23");
    }

    @Test
    public void shouldFormatConstantWithEmptyMethodBody() {
        ReadableMessage message = (ReadableMessage) Message.create("public void run() {}");
        assertThat(message.getText()).isEqualTo("public void run() {}");
    }

    @Test
    public void shouldTruncateMessage() {
        final String suffix = " [truncated to 100000 characters]";
        String longString = Strings.repeat("a", 100000);
        ReadableMessage message = (ReadableMessage) Message.create("{}", longString + "a");
        assertThat(message.getText()).isEqualTo(longString + suffix);
    }

    @Test
    public void shouldNotTruncateMessage() {
        String longString = Strings.repeat("a", 100000);
        ReadableMessage message = (ReadableMessage) Message.create("{}", longString);
        assertThat(message.getText()).isEqualTo(longString);
    }

    @Test
    public void shouldTruncateDetail() {
        final String suffix = " [truncated to 10000 characters]";
        String longString = Strings.repeat("a", 10000);
        Map<String, Object> detail = Maps.newHashMap();
        detail.put(longString, longString);
        detail.put("a", longString + "a");
        detail.put("x" + longString, "x");
        detail.put("xx" + longString, "xx" + longString);
        detail.put("list",
                ImmutableList.of(longString, longString + "a", Optional.of("x" + longString)));
        detail.put("absent", Optional.absent());
        detail.put("oa", Optional.of("a"));
        detail.put("ox", Optional.of("x" + longString));
        detail.put("nested", ImmutableMap.of(longString + "a", longString + "a"));

        ReadableMessage message = (ReadableMessage) Message.create("", detail);
        Map<String, ?> truncatedDetail = message.getDetail();
        assertThat(truncatedDetail.get(longString)).isEqualTo(longString);
        assertThat(truncatedDetail.get("a")).isEqualTo(longString + suffix);
        assertThat(truncatedDetail.get("x" + Strings.repeat("a", 9999) + suffix)).isEqualTo("x");
        assertThat(truncatedDetail.get("xx" + Strings.repeat("a", 9998) + suffix))
                .isEqualTo("xx" + Strings.repeat("a", 9998) + suffix);
        assertThat((List<?>) truncatedDetail.get("list")).containsExactly(longString,
                longString + suffix, Optional.of("x" + Strings.repeat("a", 9999) + suffix));
        assertThat(truncatedDetail.get("absent")).isEqualTo(Optional.absent());
        assertThat(truncatedDetail.get("oa")).isEqualTo(Optional.of("a"));
        assertThat(truncatedDetail.get("ox"))
                .isEqualTo(Optional.of("x" + Strings.repeat("a", 9999) + suffix));
        assertThat(((Map<?, ?>) truncatedDetail.get("nested")).get(longString + suffix))
                .isEqualTo(longString + suffix);
    }

    @Test
    public void shouldTestNotEnoughArgsForTemplate() {
        ReadableMessage message = (ReadableMessage) Message.create("{}, {} xyz {}", "test");
        assertThat(message.getText()).isEqualTo("test, <not enough args provided for template> xyz"
                + " <not enough args provided for template>");
    }

    @Test
    public void shouldTestNullTemplate() {
        ReadableMessage message = (ReadableMessage) Message.create(null);
        assertThat(message.getText()).isEqualTo("");
    }
}
