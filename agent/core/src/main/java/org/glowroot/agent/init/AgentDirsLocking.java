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
package org.glowroot.agent.init;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import com.google.common.base.Stopwatch;
import com.google.common.io.Files;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AgentDirsLocking {

    private AgentDirsLocking() {}

    public static Closeable lockAgentDirs(File tmpDir, boolean central, boolean offlineViewer)
            throws Exception {
        File lockFile = new File(tmpDir, ".lock");
        Files.createParentDirs(lockFile);
        Files.touch(lockFile);
        final RandomAccessFile openLockFile = new RandomAccessFile(lockFile, "rw");
        FileLock fileLock = openLockFile.getChannel().tryLock();
        if (fileLock == null) {
            // try for a short time in case there is O/S lag on releasing prior lock on JVM restart
            Stopwatch stopwatch = Stopwatch.createStarted();
            while (stopwatch.elapsed(SECONDS) < 2) {
                fileLock = openLockFile.getChannel().tryLock();
                if (fileLock != null) {
                    break;
                }
                MILLISECONDS.sleep(100);
            }
            if (fileLock == null) {
                throw new AgentDirsLockedException(lockFile, central, offlineViewer);
            }
        }
        lockFile.deleteOnExit();
        final FileLock fileLockFinal = fileLock;
        return new Closeable() {
            @Override
            public void close() throws IOException {
                checkNotNull(fileLockFinal);
                fileLockFinal.release();
                openLockFile.close();
            }
        };
    }

    @SuppressWarnings("serial")
    public static class AgentDirsLockedException extends Exception {

        private final File lockFile;
        private final boolean central;
        private final boolean offlineViewer;

        private AgentDirsLockedException(File lockFile, boolean central, boolean offlineViewer) {
            this.lockFile = lockFile;
            this.central = central;
            this.offlineViewer = offlineViewer;
        }

        public File getLockFile() {
            return lockFile;
        }

        public boolean isCentral() {
            return central;
        }

        public boolean isOfflineViewer() {
            return offlineViewer;
        }
    }
}
