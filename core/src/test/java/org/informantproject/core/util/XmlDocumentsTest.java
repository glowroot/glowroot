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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.net.URL;
import java.util.regex.Pattern;

import org.informantproject.local.ui.PluginJsonService;
import org.junit.Test;

import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class XmlDocumentsTest {

    @Test
    public void shouldReadInstalledPlugins() throws Exception {
        URL resourceURL = PluginJsonService.class.getClassLoader().getResource(
                "unit.test.plugin.xml");
        XmlDocuments.getValidatedDocument(Resources.newInputStreamSupplier(resourceURL));
    }

    @Test
    public void shouldWork() {
        assertThat(Pattern.matches("[a-z0-9.-]*", "ab-cd"), is(true));
    }
}
