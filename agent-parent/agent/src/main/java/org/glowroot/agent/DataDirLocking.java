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
package org.glowroot.agent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

import com.google.common.io.Files;

import static com.google.common.base.Preconditions.checkNotNull;

public class DataDirLocking {

    private DataDirLocking() {}

    public static Closeable lockDataDir(File baseDir) throws Exception {
        // lock data dir
        File tmpDir = new File(baseDir, "tmp");
        File lockFile = new File(tmpDir, ".lock");
        try {
            Files.createParentDirs(lockFile);
            Files.touch(lockFile);
        } catch (IOException e) {
            throw new BaseDirLockedException(e);
        }
        final RandomAccessFile baseDirLockFile = new RandomAccessFile(lockFile, "rw");
        final FileLock baseDirFileLock = baseDirLockFile.getChannel().tryLock();
        if (baseDirFileLock == null) {
            throw new BaseDirLockedException();
        }
        lockFile.deleteOnExit();
        return new Closeable() {
            @Override
            public void close() throws IOException {
                checkNotNull(baseDirFileLock);
                baseDirFileLock.release();
                baseDirLockFile.close();
            }
        };
    }

    @SuppressWarnings("serial")
    public static class BaseDirLockedException extends Exception {

        private BaseDirLockedException() {
            super();
        }

        private BaseDirLockedException(Throwable cause) {
            super(cause);
        }
    }
}
