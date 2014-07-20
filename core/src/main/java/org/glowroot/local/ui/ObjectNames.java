/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.util.List;

import javax.management.ObjectName;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import org.glowroot.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class ObjectNames {

    @VisibleForTesting
    static List<String> getPropertyValues(ObjectName objectName) throws IOException {
        List<String> values = Lists.newArrayList();
        char[] chars = objectName.getKeyPropertyListString().toCharArray();
        boolean inValue = false;
        boolean inQuotedValue = false;
        StringBuilder currValue = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            // check for end of key
            if (!inValue && c == '=') {
                inValue = true;
                if (i + 1 < chars.length && chars[i + 1] == '"') {
                    inQuotedValue = true;
                    i++;
                }
                continue;
            }
            // check for end of value
            if (inQuotedValue && c == '"' || inValue && !inQuotedValue && c == ',') {
                values.add(currValue.toString());
                inValue = false;
                inQuotedValue = false;
                currValue.setLength(0);
            }
            if (inQuotedValue && c == '\\') {
                c = chars[++i];
                if (c == 'n') {
                    c = '\n';
                }
            }
            if (inValue) {
                currValue.append(c);
            }
        }
        if (inValue) {
            // add the last value
            values.add(currValue.toString());
        }
        return values;
    }

    private ObjectNames() {}
}
