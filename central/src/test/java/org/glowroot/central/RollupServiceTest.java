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
package org.glowroot.central;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RollupServiceTest {

    @Test
    public void test() {
        assertThat(RollupService.millisUntilNextRollup(15000)).isEqualTo(55000);
        assertThat(RollupService.millisUntilNextRollup(30000)).isEqualTo(40000);
        assertThat(RollupService.millisUntilNextRollup(45000)).isEqualTo(25000);
        assertThat(RollupService.millisUntilNextRollup(60000)).isEqualTo(10000);
    }
}
