/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.api.internal;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class NopGlowrootService implements GlowrootService {

    public static final GlowrootService INSTANCE = new NopGlowrootService();

    @Override
    public void setTransactionType(@Nullable String transactionType) {}

    @Override
    public void setTransactionName(@Nullable String transactionName) {}

    @Override
    public void setTransactionUser(@Nullable String user) {}

    @Override
    public void addTransactionAttribute(String name, @Nullable String value) {}

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit) {}

    @Override
    public void setTransactionOuter() {}
}
