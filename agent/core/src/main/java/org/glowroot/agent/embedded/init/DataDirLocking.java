/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.embedded.init;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import com.google.common.io.Files;

import static com.google.common.base.Preconditions.checkNotNull;

public class DataDirLocking {

    private DataDirLocking() {}

    static Closeable lockDataDir(File agentDir) throws Exception {
        // lock data dir
        File tmpDir = new File(agentDir, "tmp");
        File lockFile = new File(tmpDir, ".lock");
        try {
            Files.createParentDirs(lockFile);
            Files.touch(lockFile);
        } catch (IOException e) {
            throw new AgentDirLockedException(e);
        }
        final RandomAccessFile agentDirLockFile = new RandomAccessFile(lockFile, "rw");
        FileLock agentDirFileLock = agentDirLockFile.getChannel().tryLock();
        if (agentDirFileLock == null) {
            // try again in case there is O/S lag on releasing prior lock on JVM restart
            Thread.sleep(1000);
            agentDirFileLock = agentDirLockFile.getChannel().tryLock();
            if (agentDirFileLock == null) {
                throw new AgentDirLockedException();
            }
        }
        lockFile.deleteOnExit();
        final FileLock agentDirFileLockFinal = agentDirFileLock;
        return new Closeable() {
            @Override
            public void close() throws IOException {
                checkNotNull(agentDirFileLockFinal);
                agentDirFileLockFinal.release();
                agentDirLockFile.close();
            }
        };
    }

    @SuppressWarnings("serial")
    public static class AgentDirLockedException extends Exception {

        private AgentDirLockedException() {
            super();
        }

        private AgentDirLockedException(Throwable cause) {
            super(cause);
        }
    }
}
