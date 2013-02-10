/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.testkit.internal;

import io.informant.core.util.Static;

import java.io.File;
import java.io.IOException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class TempDirs {

    private TempDirs() {}

    // copied from guava's Files.createTempDir, with added prefix
    public static File createTempDir(String prefix) {
        final int tempDirAttempts = 10000;
        File baseDir = new File(System.getProperty("java.io.tmpdir"));
        String baseName = prefix + "-" + System.currentTimeMillis() + "-";
        for (int counter = 0; counter < tempDirAttempts; counter++) {
            File tempDir = new File(baseDir, baseName + counter);
            if (tempDir.mkdir()) {
                return tempDir;
            }
        }
        throw new IllegalStateException("Failed to create directory within " + tempDirAttempts
                + " attempts (tried " + baseName + "0 to " + baseName + (tempDirAttempts - 1)
                + ')');
    }

    public static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("Could not find file to delete '" + file.getCanonicalPath()
                    + "'");
        } else if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                deleteRecursively(f);
            }
            if (!file.delete()) {
                throw new IOException("Could not delete directory '" + file.getCanonicalPath()
                        + "'");
            }
        } else if (!file.delete()) {
            throw new IOException("Could not delete file '" + file.getCanonicalPath() + "'");
        }
    }
}
