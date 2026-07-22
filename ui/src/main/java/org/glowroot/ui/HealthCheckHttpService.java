/*
 * Copyright 2018-2026 the original author or authors.
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

import com.google.common.base.Throwables;
import com.google.common.net.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;

class HealthCheckHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHttpService.class);

    private final RepoAdmin repoAdmin;

    HealthCheckHttpService(RepoAdmin repoAdmin) {
        this.repoAdmin = repoAdmin;
    }

    @Override
    public String getPermission() {
        return "";
    }

    @Override
    public CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        try {
            repoAdmin.runHealthCheck();
            return new CommonResponse(OK, MediaType.PLAIN_TEXT_UTF_8, "Glowroot OK\n");
        } catch (Exception e) {
            // Avoid a blank browser page: return a clear text body and log for operators (#766)
            Throwable root = Throwables.getRootCause(e);
            logger.error("Health check failed: {}", root.toString(), e);
            return new CommonResponse(SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                    "Glowroot health check failed: " + root.toString() + "\n");
        }
    }
}
