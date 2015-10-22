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
package org.glowroot.ui;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpServicesTest {

    @Test
    public void shouldLogRegularException() {
        // given
        Exception e = new Exception();
        // when
        assertThat(HttpServices.shouldLogException(e)).isTrue();
    }

    @Test
    public void shouldNotLogInterruptedException() {
        // given
        Exception e = new InterruptedException();
        // when
        assertThat(HttpServices.shouldLogException(e)).isFalse();
    }

    @Test
    public void shouldNotLogClosedChannelException() {
        // given
        Exception e = new ClosedChannelException();
        // when
        assertThat(HttpServices.shouldLogException(e)).isFalse();
    }

    @Test
    public void shouldNotLogBrowserDisconnectMessageException() {
        // given
        Exception e =
                new IOException("An existing connection was forcibly closed by the remote host");
        // when
        assertThat(HttpServices.shouldLogException(e)).isFalse();
    }

    @Test
    public void shouldLogRegularIOException() {
        // given
        Exception e = new IOException();
        // when
        assertThat(HttpServices.shouldLogException(e)).isTrue();
    }
}
