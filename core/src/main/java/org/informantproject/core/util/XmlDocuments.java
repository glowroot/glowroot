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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import com.google.common.base.Charsets;
import com.google.common.io.InputSupplier;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class XmlDocuments {

    private static final Logger logger = LoggerFactory.getLogger(XmlDocuments.class);

    public static Document getDocument(InputStream inputStream)
            throws ParserConfigurationException, SAXException, IOException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputStream);
    }

    public static Document getValidatedDocument(InputStream inputStream)
            throws ParserConfigurationException, SAXException, IOException {

        Document document = getDocument(inputStream);
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = schemaFactory.newSchema();
        Validator validator = schema.newValidator();
        validator.setResourceResolver(new ResourceResolver());
        validator.validate(new DOMSource(document));
        return document;
    }

    public static class ResourceResolver implements LSResourceResolver {
        public LSInput resolveResource(String type, String namespaceURI, String publicId,
                String systemId, String baseURI) {

            String prefix = "http://www.informantproject.org/xsd/";
            if (systemId.startsWith(prefix)) {
                String simpleName = systemId.substring(prefix.length());
                return new Input(Resources.newReaderSupplier(Resources.getResource(
                        "org/informantproject/core/schema/" + simpleName), Charsets.UTF_8));
            } else {
                logger.error("unexpected xml resource requested: type={}, namespaceURI={},"
                        + " publicId={}, systemId={}, baseURI={}", new Object[] { namespaceURI,
                        publicId, systemId, baseURI });
                return null;
            }
        }
    }

    private static class Input implements LSInput {
        private static Logger logger = LoggerFactory.getLogger(Input.class);
        private final InputSupplier<? extends Reader> inputSupplier;
        public Input(InputSupplier<? extends Reader> inputSupplier) {
            this.inputSupplier = inputSupplier;
        }
        public String getPublicId() {
            return null;
        }
        public void setPublicId(String publicId) {}
        public String getSystemId() {
            return null;
        }
        public void setSystemId(String systemId) {}
        public Reader getCharacterStream() {
            try {
                return inputSupplier.getInput();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return null;
            }
        }
        public void setCharacterStream(Reader characterStream) {}
        public InputStream getByteStream() {
            return null;
        }
        public void setByteStream(InputStream byteStream) {}
        public String getStringData() {
            return null;
        }
        public void setStringData(String stringData) {}
        public String getBaseURI() {
            return null;
        }
        public void setBaseURI(String baseURI) {}
        public String getEncoding() {
            return null;
        }
        public void setEncoding(String encoding) {}
        public boolean getCertifiedText() {
            return false;
        }
        public void setCertifiedText(boolean certifiedText) {}
    }
}
