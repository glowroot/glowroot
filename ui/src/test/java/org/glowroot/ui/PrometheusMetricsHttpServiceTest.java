/*
 * Copyright 2026 the original author or authors.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonResponse;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class PrometheusMetricsHttpServiceTest {

    private static final String PROP = PrometheusMetricsHttpService.ENABLED_PROPERTY;

    private String previousProp;

    @BeforeEach
    public void saveProperty() {
        previousProp = System.getProperty(PROP);
    }

    @AfterEach
    public void restoreProperty() {
        if (previousProp == null) {
            System.clearProperty(PROP);
        } else {
            System.setProperty(PROP, previousProp);
        }
    }

    @Test
    public void shouldReturn404WhenDisabled() throws Exception {
        System.clearProperty(PROP);
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        PrometheusMetricsHttpService service =
                new PrometheusMetricsHttpService(repoAdmin, "0.14.8-SNAPSHOT");

        CommonResponse response = service.handleRequest(null, null);

        assertThat(response.getStatus()).isEqualTo(NOT_FOUND);
        verifyNoInteractions(repoAdmin);
    }

    @Test
    public void shouldReturnPrometheusBodyWhenEnabled() throws Exception {
        System.setProperty(PROP, "true");
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        when(repoAdmin.getH2DataFileSize()).thenReturn(12345L);
        PrometheusMetricsHttpService service =
                new PrometheusMetricsHttpService(repoAdmin, "0.14.8-SNAPSHOT");

        CommonResponse response = service.handleRequest(null, null);

        verify(repoAdmin).getH2DataFileSize();
        assertThat(response.getStatus()).isEqualTo(OK);
        assertThat(response.getHeaders().get(CONTENT_TYPE)).contains("version=0.0.4");
        String body = (String) response.getContent();
        assertThat(body).contains("# TYPE glowroot_up gauge");
        assertThat(body).contains("glowroot_up 1");
        assertThat(body).contains("glowroot_h2_data_file_bytes 12345");
        assertThat(body).contains("glowroot_info{version=\"0.14.8-SNAPSHOT\"} 1");
        assertThat(service.getPermission()).isEmpty();
    }

    @Test
    public void shouldEscapeBackslashQuoteAndNewlineInLabels() {
        assertThat(PrometheusMetricsHttpService.escapeLabel("a\\b\"c\nd"))
                .isEqualTo("a\\\\b\\\"c\\nd");
    }
}
