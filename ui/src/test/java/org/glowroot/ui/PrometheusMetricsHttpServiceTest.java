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

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.RepoAdmin;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.mockito.ArgumentCaptor;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        LiveAggregateRepository live = mock(LiveAggregateRepository.class);
        Clock clock = mock(Clock.class);
        PrometheusMetricsHttpService service =
                new PrometheusMetricsHttpService(repoAdmin, "0.14.8-SNAPSHOT", live, clock);

        CommonResponse response = service.handleRequest(null, null);

        assertThat(response.getStatus()).isEqualTo(NOT_FOUND);
        verifyNoInteractions(repoAdmin, live, clock);
    }

    @Test
    public void shouldReturnPrometheusBodyWhenEnabled() throws Exception {
        System.setProperty(PROP, "true");
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        when(repoAdmin.getH2DataFileSize()).thenReturn(12345L);
        LiveAggregateRepository live = mock(LiveAggregateRepository.class);
        when(live.getTransactionTypes("")).thenReturn(Collections.<String>emptySet());
        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(1_000_000L);
        PrometheusMetricsHttpService service =
                new PrometheusMetricsHttpService(repoAdmin, "0.14.8-SNAPSHOT", live, clock);

        CommonResponse response = service.handleRequest(null, null);

        verify(repoAdmin).getH2DataFileSize();
        assertThat(response.getStatus()).isEqualTo(OK);
        assertThat(response.getHeaders().get(CONTENT_TYPE)).contains("version=0.0.4");
        String body = (String) response.getContent();
        assertThat(body).contains("# TYPE glowroot_up gauge");
        assertThat(body).contains("glowroot_up 1");
        assertThat(body).contains("glowroot_h2_data_file_bytes 12345");
        assertThat(body).contains("glowroot_info{version=\"0.14.8-SNAPSHOT\"} 1");
        assertThat(body).doesNotContain("glowroot_transaction_count");
        assertThat(service.getPermission()).isEmpty();
    }

    @Test
    public void shouldEmitPerTransactionTypeGauges() throws Exception {
        System.setProperty(PROP, "true");
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        when(repoAdmin.getH2DataFileSize()).thenReturn(1L);
        LiveAggregateRepository live = mock(LiveAggregateRepository.class);
        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(1_000_000L);
        when(live.getTransactionTypes("")).thenReturn(Collections.singleton("Web"));

        ThroughputAggregate thr = mock(ThroughputAggregate.class);
        when(thr.transactionCount()).thenReturn(100L);
        when(thr.errorCount()).thenReturn(5L);
        when(live.getThroughputAggregates(eq(""), any(AggregateQuery.class)))
                .thenReturn(new LiveResult<ThroughputAggregate>(
                        Collections.singletonList(thr), 1_000_000L));

        OverviewAggregate ov = mock(OverviewAggregate.class);
        when(ov.totalDurationNanos()).thenReturn(200_000_000_000d);
        when(ov.transactionCount()).thenReturn(100L);
        when(live.getOverviewAggregates(eq(""), any(AggregateQuery.class)))
                .thenReturn(new LiveResult<OverviewAggregate>(
                        Collections.singletonList(ov), 1_000_000L));

        PrometheusMetricsHttpService service =
                new PrometheusMetricsHttpService(repoAdmin, "1.0", live, clock);
        String body = (String) service.handleRequest(null, null).getContent();

        ArgumentCaptor<AggregateQuery> queryCaptor = ArgumentCaptor.forClass(AggregateQuery.class);
        verify(live).getThroughputAggregates(eq(""), queryCaptor.capture());
        AggregateQuery query = queryCaptor.getValue();
        assertThat(query.from()).isEqualTo(940_000L);
        assertThat(query.to()).isEqualTo(1_000_000L);
        assertThat(query.rollupLevel()).isEqualTo(0);
        assertThat(query.transactionType()).isEqualTo("Web");
        assertThat(query.transactionName()).isNull();

        assertThat(body).contains("glowroot_transaction_count{transaction_type=\"Web\"} 100");
        assertThat(body).contains("glowroot_transaction_error_count{transaction_type=\"Web\"} 5");
        assertThat(body).contains("glowroot_transaction_error_rate{transaction_type=\"Web\"} 0.05");
        assertThat(body).contains(
                "glowroot_transaction_avg_duration_seconds{transaction_type=\"Web\"} 2.0");
    }

    @Test
    public void shouldTreatNullErrorCountAsZeroAndZeroRatesWhenNoTx() throws Exception {
        System.setProperty(PROP, "true");
        RepoAdmin repoAdmin = mock(RepoAdmin.class);
        when(repoAdmin.getH2DataFileSize()).thenReturn(0L);
        LiveAggregateRepository live = mock(LiveAggregateRepository.class);
        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(1_000_000L);
        when(live.getTransactionTypes("")).thenReturn(Collections.singleton("Background"));

        ThroughputAggregate thr = mock(ThroughputAggregate.class);
        when(thr.transactionCount()).thenReturn(0L);
        when(thr.errorCount()).thenReturn(null);
        when(live.getThroughputAggregates(eq(""), any(AggregateQuery.class)))
                .thenReturn(new LiveResult<ThroughputAggregate>(
                        Collections.singletonList(thr), 1_000_000L));
        when(live.getOverviewAggregates(eq(""), any(AggregateQuery.class))).thenReturn(null);

        String body = (String) new PrometheusMetricsHttpService(repoAdmin, "1.0", live, clock)
                .handleRequest(null, null)
                .getContent();

        assertThat(body).contains(
                "glowroot_transaction_count{transaction_type=\"Background\"} 0");
        assertThat(body).contains(
                "glowroot_transaction_error_count{transaction_type=\"Background\"} 0");
        assertThat(body).contains(
                "glowroot_transaction_error_rate{transaction_type=\"Background\"} 0.0");
        assertThat(body).contains(
                "glowroot_transaction_avg_duration_seconds{transaction_type=\"Background\"} 0.0");
    }

    @Test
    public void shouldEscapeBackslashQuoteAndNewlineInLabels() {
        assertThat(PrometheusMetricsHttpService.escapeLabel("a\\b\"c\nd"))
                .isEqualTo("a\\\\b\\\"c\\nd");
    }
}
