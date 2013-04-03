/**
 * Copyright 2013 the original author or authors.
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
package io.informant.container.local;

import java.io.File;

import checkers.nullness.quals.Nullable;

import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TempDirs;
import io.informant.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class LocalContainer extends GenericLocalContainer<AppUnderTest> implements Container {

    public static Container createWithFileDb(int uiPort) throws Exception {
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        return new LocalContainer(dataDir, uiPort, true, false);
    }

    public static Container createWithFileDb(File dataDir) throws Exception {
        return new LocalContainer(dataDir, 0, true, false);
    }

    public LocalContainer(@Nullable File dataDir, int uiPort, boolean useFileDb, boolean shared)
            throws Exception {
        super(dataDir, uiPort, useFileDb, shared, AppUnderTest.class,
                AppUnderTestExecutor.INSTANCE);
    }

    private static class AppUnderTestExecutor implements AppExecutor<AppUnderTest> {
        private static final AppUnderTestExecutor INSTANCE = new AppUnderTestExecutor();
        public void executeApp(AppUnderTest appUnderTest) throws Exception {
            appUnderTest.executeApp();
        }
    }
}
