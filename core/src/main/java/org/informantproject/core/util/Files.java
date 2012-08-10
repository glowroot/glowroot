/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.util;

import java.io.File;
import java.io.IOException;

import org.informantproject.core.util.UnitTests.OnlyUsedByTests;

/**
 * Only used by tests (either directly or through methods that are {@link OnlyUsedByTests}).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class Files {

    private static final int TEMP_DIR_ATTEMPTS = 10000;

    public static void delete(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("could not find file to delete '" + file.getCanonicalPath()
                    + "'");
        } else if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                delete(f);
            }
            if (!file.delete()) {
                throw new IOException("could not delete directory '" + file.getCanonicalPath()
                        + "'");
            }
        } else if (!file.delete()) {
            throw new IOException("could not delete file '" + file.getCanonicalPath() + "'");
        }
    }

    // copied from guava's Files.createTempDir, with added prefix
    public static File createTempDir(String prefix) {
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = prefix + "-" + System.currentTimeMillis() + "-";

        for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create directory within " + TEMP_DIR_ATTEMPTS
                + " attempts (tried " + baseName + "0 to " + baseName + (TEMP_DIR_ATTEMPTS - 1)
                + ')');
    }

    private Files() {}
}
