/*
 * Copyright 2017 the original author or authors.
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

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.Test;

import org.glowroot.common.util.Clock;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class CommonHandlerTest {

    // this constant is from org.h2.api.ErrorCode.STATEMENT_WAS_CANCELED
    // (but h2 jar is not a dependency of glowroot-ui)
    private static final int H2_STATEMENT_WAS_CANCELED = 57014;

    private static final CommonHandler HTTP_SERVER_HANDLER =
            new CommonHandler(false, mock(LayoutService.class), new HashMap<Pattern, HttpService>(),
                    mock(HttpSessionManager.class), new ArrayList<Object>(), mock(Clock.class));

    @Test
    public void shouldCreateJsonServiceExceptionResponse() throws Exception {
        // given
        Exception e = new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED,
                new IllegalStateException("An ignored message"));
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseWithMessage() throws Exception {
        // given
        Exception e = new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED, "A message");
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"A message\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseFromInvocationTargetException()
            throws Exception {
        // given
        Exception e = new InvocationTargetException(
                new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED,
                        new IllegalStateException("An ignored message")));
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateJsonServiceExceptionResponseWithMessageFromInvocationTargetException()
            throws Exception {
        // given
        Exception e = new InvocationTargetException(
                new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED, "A message"));
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":\"A message\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.PRECONDITION_FAILED);
    }

    @Test
    public void shouldCreateSqlTimeoutResponse() throws Exception {
        // given
        Exception e = new SQLException("", "", H2_STATEMENT_WAS_CANCELED);
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        assertThat(content).isEqualTo("{\"message\":"
                + "\"Query timed out (timeout is configurable under Configuration > Advanced)\"}");
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.REQUEST_TIMEOUT);
    }

    @Test
    public void shouldCreateNonTimeoutSqlExceptionResponse() throws Exception {
        // given
        Exception e = new SQLException("Another message", "", H2_STATEMENT_WAS_CANCELED + 1);
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Another message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void shouldCreateExceptionResponse() throws Exception {
        // given
        Exception e = new Exception("Banother message");
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Banother message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void shouldCreateWrappedExceptionResponse() throws Exception {
        // given
        Exception e = new Exception(new Exception(new Exception("Wrapped message")));
        // when
        CommonResponse httpResponse = HTTP_SERVER_HANDLER.newHttpResponseFromException(
                mock(CommonRequest.class), mock(Authentication.class), e);
        // then
        String content = ((ByteBuf) httpResponse.getContent()).toString(Charsets.ISO_8859_1);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(content);
        assertThat(node.get("message").asText()).isEqualTo("Wrapped message");
        assertThat(node.get("stackTrace")).isNotNull();
        assertThat(httpResponse.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void shouldStripContextPath() {
        assertThat(HttpServerHandler.stripContextPath("/one", "/")).isEqualTo("/one");
        assertThat(HttpServerHandler.stripContextPath("/", "/")).isEqualTo("/");
        assertThat(HttpServerHandler.stripContextPath("/one", "/one")).isEqualTo("/");
        assertThat(HttpServerHandler.stripContextPath("/one/", "/one")).isEqualTo("/");
        assertThat(HttpServerHandler.stripContextPath("/one/two", "/one")).isEqualTo("/two");
        assertThat(HttpServerHandler.stripContextPath("/one/two/", "/one")).isEqualTo("/two/");
    }
}
