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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

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

    public static Document getValidatedDocument(InputSupplier<InputStream> inputSupplier)
            throws ParserConfigurationException, SAXException, IOException {

        // validate first with SAX
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(true);
        SAXParser parser = factory.newSAXParser();
        parser.setProperty("http://java.sun.com/xml/jaxp/properties/schemaLanguage",
                XMLConstants.W3C_XML_SCHEMA_NS_URI);
        XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(new ResourceEntityResolver());
        reader.setErrorHandler(new LoggingErrorHandler());
        reader.parse(new InputSource(inputSupplier.getInput()));
        // then return DOM
        return getDocument(inputSupplier.getInput());
    }

    private static class ResourceEntityResolver implements EntityResolver {
        public InputSource resolveEntity(String publicId, String systemId) throws IOException {
            String prefix = "http://www.informantproject.org/xsd/";
            if (systemId.startsWith(prefix)) {
                String simpleName = systemId.substring(prefix.length());
                return new InputSource(Resources.newReaderSupplier(Resources.getResource(
                        "org/informantproject/core/schema/" + simpleName), Charsets.UTF_8)
                        .getInput());
            } else {
                logger.error("unexpected xml resource requested: publicId={}, systemId={}",
                        new Object[] { publicId, systemId });
                return null;
            }
        }
    }

    private static class LoggingErrorHandler implements ErrorHandler {
        public void warning(SAXParseException e) throws SAXException {
            logger.warn(e.getMessage(), e);
        }
        public void error(SAXParseException e) throws SAXException {
            logger.error(e.getMessage(), e);
        }
        public void fatalError(SAXParseException e) throws SAXException {
            logger.error(e.getMessage(), e);
            throw e;
        }
    }
}
