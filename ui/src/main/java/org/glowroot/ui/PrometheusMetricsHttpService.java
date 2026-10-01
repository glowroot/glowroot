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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.common.net.MediaType;

import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class PrometheusMetricsHttpService implements HttpService {

    static final String ENABLED_PROPERTY = "glowroot.metrics.prometheus";

    private static final long WINDOW_MILLIS = 60_000L;

    private static final MediaType PROMETHEUS_TEXT =
            MediaType.parse("text/plain; version=0.0.4; charset=utf-8");

    private final RepoAdmin repoAdmin;
    private final String version;
    private final LiveAggregateRepository liveAggregateRepository;
    private final Clock clock;

    PrometheusMetricsHttpService(RepoAdmin repoAdmin, String version,
            LiveAggregateRepository liveAggregateRepository, Clock clock) {
        this.repoAdmin = repoAdmin;
        this.version = checkNotNull(version);
        this.liveAggregateRepository = checkNotNull(liveAggregateRepository);
        this.clock = checkNotNull(clock);
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
        StringBuilder sb = new StringBuilder(512);
        sb.append("# HELP glowroot_up Always 1 when this scrape succeeds\n");
        sb.append("# TYPE glowroot_up gauge\n");
        sb.append("glowroot_up 1\n");
        sb.append("# HELP glowroot_h2_data_file_bytes Embedded H2 data.mv.db size in bytes\n");
        sb.append("# TYPE glowroot_h2_data_file_bytes gauge\n");
        sb.append("glowroot_h2_data_file_bytes ").append(h2Bytes).append('\n');
        sb.append("# HELP glowroot_info Glowroot build info\n");
        sb.append("# TYPE glowroot_info gauge\n");
        sb.append("glowroot_info{version=\"").append(escapeLabel(version)).append("\"} 1\n");
        appendTransactionTypeMetrics(sb);
        return new CommonResponse(OK, PROMETHEUS_TEXT, sb.toString());
    }

    private void appendTransactionTypeMetrics(StringBuilder sb) {
        long to = clock.currentTimeMillis();
        long from = to - WINDOW_MILLIS;
        Set<String> types = liveAggregateRepository.getTransactionTypes("");
        if (types.isEmpty()) {
            return;
        }
        List<String> sortedTypes = new ArrayList<String>(types);
        Collections.sort(sortedTypes);

        StringBuilder counts = new StringBuilder();
        StringBuilder errors = new StringBuilder();
        StringBuilder rates = new StringBuilder();
        StringBuilder avgs = new StringBuilder();

        for (String type : sortedTypes) {
            AggregateQuery query = ImmutableAggregateQuery.builder()
                    .transactionType(type)
                    .from(from)
                    .to(to)
                    .rollupLevel(0)
                    .build();

            long txCount = 0;
            long errorCount = 0;
            LiveResult<ThroughputAggregate> thr =
                    liveAggregateRepository.getThroughputAggregates("", query);
            if (thr != null) {
                for (ThroughputAggregate a : thr.get()) {
                    txCount += a.transactionCount();
                    Long ec = a.errorCount();
                    if (ec != null) {
                        errorCount += ec;
                    }
                }
            }

            double totalDurationNanos = 0;
            long overviewTxCount = 0;
            LiveResult<OverviewAggregate> ov =
                    liveAggregateRepository.getOverviewAggregates("", query);
            if (ov != null) {
                for (OverviewAggregate a : ov.get()) {
                    totalDurationNanos += a.totalDurationNanos();
                    overviewTxCount += a.transactionCount();
                }
            }

            double errorRate = txCount == 0 ? 0.0 : (double) errorCount / (double) txCount;
            // Avg uses overview totals/counts so numerator and denominator stay aligned
            double avgSec = overviewTxCount == 0 ? 0.0
                    : (totalDurationNanos / (double) overviewTxCount) / 1_000_000_000.0;

            String label = "transaction_type=\"" + escapeLabel(type) + "\"";
            counts.append("glowroot_transaction_count{").append(label).append("} ")
                    .append(txCount).append('\n');
            errors.append("glowroot_transaction_error_count{").append(label).append("} ")
                    .append(errorCount).append('\n');
            rates.append("glowroot_transaction_error_rate{").append(label).append("} ")
                    .append(Double.toString(errorRate)).append('\n');
            avgs.append("glowroot_transaction_avg_duration_seconds{").append(label).append("} ")
                    .append(Double.toString(avgSec)).append('\n');
        }

        sb.append("# HELP glowroot_transaction_count Transactions observed in the last 60s window\n");
        sb.append("# TYPE glowroot_transaction_count gauge\n");
        sb.append(counts);
        sb.append("# HELP glowroot_transaction_error_count Errors observed in the last 60s window\n");
        sb.append("# TYPE glowroot_transaction_error_count gauge\n");
        sb.append(errors);
        sb.append("# HELP glowroot_transaction_error_rate error_count/count in the last 60s window"
                + " (0 if count=0)\n");
        sb.append("# TYPE glowroot_transaction_error_rate gauge\n");
        sb.append(rates);
        sb.append("# HELP glowroot_transaction_avg_duration_seconds Mean duration seconds in the"
                + " last 60s window (0 if count=0)\n");
        sb.append("# TYPE glowroot_transaction_avg_duration_seconds gauge\n");
        sb.append(avgs);
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
