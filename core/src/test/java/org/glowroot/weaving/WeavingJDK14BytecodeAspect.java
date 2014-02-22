/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeavingJDK14BytecodeAspect {

    @Pointcut(typeName = "org.apache.commons.lang.StringUtils", methodName = "isEmpty",
            methodArgs = {"java.lang.String"}, metricName = "is empty")
    public static class BasicAdvice {
        @OnBefore
        public static void onBefore(@SuppressWarnings("unused") @BindMethodName String test) {}
    }
}
