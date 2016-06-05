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
package org.glowroot.agent.plugin.play;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SimplifiedRouteTest {

    @Test
    public void test() {
        assertThat(Play2xAspect.simplifiedRoute("")).isEqualTo("");
        assertThat(Play2xAspect.simplifiedRoute("/one")).isEqualTo("/one");
        assertThat(Play2xAspect.simplifiedRoute("/one/$two<[^/]+>/three"))
                .isEqualTo("/one/*/three");
        assertThat(Play2xAspect.simplifiedRoute("/assets/$file<.+>")).isEqualTo("/assets/**");
        assertThat(Play2xAspect.simplifiedRoute("/custom/$xyz<xyz[^/]+>/three"))
                .isEqualTo("/custom/xyz*/three");
    }
}
