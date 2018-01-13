/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.live;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

import com.google.common.io.ByteStreams;

import org.glowroot.agent.live.JvmTool.InputStreamProcessor;

class JStackTool {

    private JStackTool() {}

    static String run(long pid, boolean allowAttachSelf, @Nullable File glowrootJarFile)
            throws Exception {
        return JvmTool.run(pid, "remoteDataDump", new JStackProcessor(), allowAttachSelf,
                glowrootJarFile);
    }

    private static class JStackProcessor implements InputStreamProcessor<String> {
        @Override
        public String process(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteStreams.copy(in, baos);
            return new String(baos.toByteArray());
        }
    }
}
