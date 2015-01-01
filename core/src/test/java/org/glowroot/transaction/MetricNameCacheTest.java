/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.transaction;

import org.junit.Test;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.transaction.model.MetricNameImpl;

import static org.assertj.core.api.Assertions.assertThat;

public class MetricNameCacheTest {

    @Test
    public void testNullClass() {
        // given
        MetricNameCache metricNameCache = new MetricNameCache();
        // when
        MetricName metricName = metricNameCache.getName(null);
        // then
        assertThat(((MetricNameImpl) metricName).name()).isEqualTo("unknown");
    }

    @Test
    public void testClassWithoutPointcutAnnotation() {
        // given
        MetricNameCache metricNameCache = new MetricNameCache();
        // when
        MetricName metricName = metricNameCache.getName(A.class);
        // then
        assertThat(((MetricNameImpl) metricName).name()).isEqualTo("unknown");
    }

    @Test
    public void testClassWithEmptyMetricName() {
        // given
        MetricNameCache metricNameCache = new MetricNameCache();
        // when
        MetricName metricName = metricNameCache.getName(B.class);
        // then
        assertThat(((MetricNameImpl) metricName).name()).isEqualTo("unknown");
    }

    @Test
    public void testNormal() {
        // given
        MetricNameCache metricNameCache = new MetricNameCache();
        // when
        MetricName metricName = metricNameCache.getName(C.class);
        // then
        assertThat(((MetricNameImpl) metricName).name()).isEqualTo("z");
    }

    private static class A {}

    @Pointcut(className = "x", methodName = "y", methodParameterTypes = {})
    private static class B {}

    @Pointcut(className = "x", methodName = "y", methodParameterTypes = {}, metricName = "z")
    private static class C {}
}
