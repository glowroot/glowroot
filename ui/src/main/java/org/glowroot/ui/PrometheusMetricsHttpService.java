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

import com.google.common.net.MediaType;

import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class PrometheusMetricsHttpService implements HttpService {

    static final String ENABLED_PROPERTY = "glowroot.metrics.prometheus";

    private static final MediaType PROMETHEUS_TEXT =
            MediaType.parse("text/plain; version=0.0.4; charset=utf-8");

    private final RepoAdmin repoAdmin;
    private final String version;

    PrometheusMetricsHttpService(RepoAdmin repoAdmin, String version) {
        this.repoAdmin = repoAdmin;
        this.version = checkNotNull(version);
    }

    @Override
    public String getPermission() {
        return "";
    }

    @Override
    public CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return new CommonResponse(NOT_FOUND);
        }
        long h2Bytes = repoAdmin.getH2DataFileSize();
        StringBuilder sb = new StringBuilder(256);
        sb.append("# HELP glowroot_up Always 1 when this scrape succeeds\n");
        sb.append("# TYPE glowroot_up gauge\n");
        sb.append("glowroot_up 1\n");
        sb.append("# HELP glowroot_h2_data_file_bytes Embedded H2 data.mv.db size in bytes\n");
        sb.append("# TYPE glowroot_h2_data_file_bytes gauge\n");
        sb.append("glowroot_h2_data_file_bytes ").append(h2Bytes).append('\n');
        sb.append("# HELP glowroot_info Glowroot build info\n");
        sb.append("# TYPE glowroot_info gauge\n");
        sb.append("glowroot_info{version=\"").append(escapeLabel(version)).append("\"} 1\n");
        return new CommonResponse(OK, PROMETHEUS_TEXT, sb.toString());
    }

    // Prometheus label escaping: \ -> \\, " -> \", newline -> \n
    static String escapeLabel(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
