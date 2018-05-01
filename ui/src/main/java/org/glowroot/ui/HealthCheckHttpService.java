/*
 * Copyright 2018 the original author or authors.
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

import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class HealthCheckHttpService implements HttpService {

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
        repoAdmin.runHealthCheck();
        return new CommonResponse(OK);
    }
}
