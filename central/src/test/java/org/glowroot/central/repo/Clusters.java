/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.SocketOptions;

class Clusters {

    static Cluster newCluster() {
        return Cluster.builder().addContactPoint("127.0.0.1")
                // long read timeout is sometimes needed on slow travis ci machines
                .withSocketOptions(new SocketOptions().setReadTimeoutMillis(30000))
                // let driver know that only idempotent queries are used so it will retry on timeout
                .withQueryOptions(new QueryOptions().setDefaultIdempotence(true))
                .build();
    }
}
