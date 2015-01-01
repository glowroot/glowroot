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
package org.glowroot.api;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.assertj.core.api.Assertions.assertThat;

public class OptionalTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testAbsent() {
        assertThat(Optional.absent().isPresent()).isFalse();
        assertThat(Optional.absent().orNull()).isNull();
        assertThat(Optional.absent().or("xyz")).isEqualTo("xyz");
    }

    @Test
    public void testAbsentClassVariant() {
        assertThat(Optional.absent(String.class)).isEqualTo(Optional.absent());
    }

    @Test
    public void testGetOnAbsentThrowsException() {
        thrown.expect(IllegalStateException.class);
        Optional.absent().get();
    }

    @Test
    public void testPresent() {
        assertThat(Optional.of("abc").isPresent()).isTrue();
        assertThat(Optional.of("abc").get()).isEqualTo("abc");
        assertThat(Optional.of("abc").orNull()).isEqualTo("abc");
        assertThat(Optional.of("abc").or("xyz")).isEqualTo("abc");
    }

    @Test
    public void testFromNullableAbsent() {
        assertThat(Optional.fromNullable(null)).isEqualTo(Optional.absent());
    }

    @Test
    public void testFromNullablePresent() {
        assertThat(Optional.fromNullable("abc")).isEqualTo(Optional.of("abc"));
    }

    @Test
    public void testEquals() {
        assertThat(Optional.of("abc")).isEqualTo(Optional.of("abc"));
        assertThat(Optional.of("abc")).isNotEqualTo("abc");
        assertThat(Optional.absent()).isEqualTo(Optional.absent());
        assertThat(Optional.absent()).isNotEqualTo(null);
    }

    @Test
    public void testHashCode() {
        assertThat(Optional.of("abc").hashCode()).isEqualTo(Optional.of("abc").hashCode());
        assertThat(Optional.absent().hashCode()).isEqualTo(Optional.absent().hashCode());
    }
}
