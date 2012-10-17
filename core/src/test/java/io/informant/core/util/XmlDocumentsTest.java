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
package io.informant.core.util;

import static org.fest.assertions.api.Assertions.assertThat;

import java.net.URL;

import org.junit.Test;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class XmlDocumentsTest {

    @Test
    public void shouldReadValidPluginXml() throws Exception {
        URL url = Resources.getResource("unit.test.plugin.valid.xml");
        XmlDocuments.newValidatedDocument(Resources.newInputStreamSupplier(url),
                new ThrowingErrorHandler());
    }

    @Test
    public void shouldErrorOnInvalidPluginXml() throws Exception {
        URL url = Resources.getResource("unit.test.plugin.invalid.xml");
        SAXParseException exception = null;
        try {
            XmlDocuments.newValidatedDocument(Resources.newInputStreamSupplier(url),
                    new ThrowingErrorHandler());
        } catch (SAXParseException e) {
            exception = e;
        }
        assertThat(exception).isNotNull();
    }

    private static class ThrowingErrorHandler implements ErrorHandler {
        public void warning(SAXParseException e) throws SAXException {
            throw e;
        }
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}
