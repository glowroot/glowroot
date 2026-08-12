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

import org.junit.jupiter.api.Test;

import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonResponse;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class HealthCheckHttpServiceTest {

    @Test
    public void shouldReturnOkWhenHealthCheckPasses() throws Exception {
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        HealthCheckHttpService service = new HealthCheckHttpService(repoAdmin);

        CommonResponse response = service.handleRequest(null, null);

        verify(repoAdmin).runHealthCheck();
        assertThat(response.getStatus()).isEqualTo(OK);
        assertThat((String) response.getContent()).isEqualTo("Glowroot OK\n");
    }

    @Test
    public void shouldReturnServiceUnavailableWhenHealthCheckFails() throws Exception {
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        doThrow(new RuntimeException("Cassandra unreachable")).when(repoAdmin).runHealthCheck();
        HealthCheckHttpService service = new HealthCheckHttpService(repoAdmin);

        CommonResponse response = service.handleRequest(null, null);

        verify(repoAdmin).runHealthCheck();
        assertThat(response.getStatus()).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat((String) response.getContent()).contains("Glowroot readiness check failed: java.lang.RuntimeException: Cassandra unreachable");
    }
}
