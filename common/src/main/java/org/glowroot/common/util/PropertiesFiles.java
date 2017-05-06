/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class PropertiesFiles {

    private PropertiesFiles() {}

    public static Properties load(File propFile) throws IOException {
        Properties props = new Properties();
        InputStream in = new FileInputStream(propFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        return props;
    }

    public static void upgradeIfNeeded(File propFile, Map<String, String> findReplacePairs)
            throws IOException {
        // properties files must be ISO_8859_1
        String content = Files.toString(propFile, Charsets.ISO_8859_1);
        boolean modified = false;
        for (Map.Entry<String, String> entry : findReplacePairs.entrySet()) {
            String find = entry.getKey();
            if (content.contains(find)) {
                content = content.replace(find, entry.getValue());
                modified = true;
            }
        }
        if (modified) {
            Files.write(content, propFile, Charsets.ISO_8859_1);
        }
    }
}
