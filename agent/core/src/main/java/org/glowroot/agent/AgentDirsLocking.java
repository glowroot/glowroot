/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import org.checkerframework.checker.nullness.qual.Nullable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

//LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
class AgentDirsLocking {

    private AgentDirsLocking() {}

    static @Nullable Closeable tryLockAgentDirs(File tmpDir, boolean wait) throws Exception {
        NotGuava.mkdirs(tmpDir);
        File lockFile = new File(tmpDir, ".lock");
        touch(lockFile);
        final RandomAccessFile openLockFile = new RandomAccessFile(lockFile, "rw");
        FileLock fileLock = openLockFile.getChannel().tryLock();
        if (fileLock == null) {
            if (!wait) {
                return null;
            }
            // try for a short time in case there is O/S lag on releasing prior lock on JVM restart
            long startTimeMillis = System.currentTimeMillis();
            do {
                MILLISECONDS.sleep(100);
                fileLock = openLockFile.getChannel().tryLock();
                if (fileLock != null) {
                    break;
                }
            } while (System.currentTimeMillis() < startTimeMillis + 2000);
            if (fileLock == null) {
                return null;
            }
        }
        lockFile.deleteOnExit();
        final FileLock fileLockFinal = fileLock;
        return new Closeable() {
            @Override
            public void close() throws IOException {
                NotGuava.checkNotNull(fileLockFinal);
                fileLockFinal.release();
                openLockFile.close();
            }
        };
    }

    // copied from guava Files.touch()
    private static void touch(File file) throws IOException {
        if (!file.createNewFile() && !file.setLastModified(System.currentTimeMillis())) {
            throw new IOException("Unable to update modification time of " + file);
        }
    }
}
