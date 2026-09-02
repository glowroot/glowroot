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
package org.glowroot.central;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CentralModuleCassandraContactPointsTest {

    @Test
    public void shouldLeaveContactPointsUnresolvedForDnsRefresh() {
        List<InetSocketAddress> points =
                CentralModule.cassandraContactPoints(List.of("cassandra.example.svc"), 9042);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).isUnresolved()).isTrue();
        assertThat(points.get(0).getHostString()).isEqualTo("cassandra.example.svc");
        assertThat(points.get(0).getPort()).isEqualTo(9042);
    }
}
