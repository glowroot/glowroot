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
package org.glowroot.collector;

import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;

import org.glowroot.weaving.AdviceBuilder;

@SuppressWarnings("serial")
public class PatternObjectNameQueryExp implements QueryExp {

    private final Pattern pattern;

    public PatternObjectNameQueryExp(String text) {
        this.pattern = Pattern.compile(AdviceBuilder.buildSimplePattern(text),
                Pattern.CASE_INSENSITIVE);
    }

    @Override
    public boolean apply(ObjectName name) {
        return pattern.matcher(name.toString()).matches();
    }

    @Override
    public void setMBeanServer(MBeanServer s) {}
}
