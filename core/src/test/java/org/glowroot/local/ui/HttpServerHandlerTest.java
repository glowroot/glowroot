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
package org.glowroot.local.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.h2.api.ErrorCode;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpServerHandlerTest {

    @Test
    public void shouldCreateJsonServiceExceptionResponse() {
        // given
        Exception e = new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED,
                new IllegalStateException("An ignored message"));
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseWithMessage() {
        // given
        Exception e = new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED, "A message");
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"A message\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseFromInvocationTargetException() {
        // given
        Exception e = new InvocationTargetException(
                new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED,
                        new IllegalStateException("An ignored message")));
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseWithMessageFromInvocationTargetException() {
        // given
        Exception e = new InvocationTargetException(
                new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED, "A message"));
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"A message\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateSqlTimeoutResponse() {
        // given
        Exception e = new SQLException("", "", ErrorCode.STATEMENT_WAS_CANCELED);
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":"
                + "\"Query timed out (timeout is configurable under Configuration > Advanced)\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.REQUEST_TIMEOUT);
    }

    @Test
    public void shouldCreateNonTimeoutSqlExceptionResponse() throws IOException {
        // given
        Exception e = new SQLException("Another message", "", ErrorCode.STATEMENT_WAS_CANCELED + 1);
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Another message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void shouldCreateExceptionResponse() throws IOException {
        // given
        Exception e = new Exception("Banother message");
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Banother message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void shouldCreateWrappedExceptionResponse() throws IOException {
        // given
        Exception e = new Exception(new Exception(new Exception("Wrapped message")));
        // when
        FullHttpResponse httpResponse = HttpServerHandler.newHttpResponseFromException(e);
        // then
        String content = httpResponse.content().toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Wrapped message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
}
