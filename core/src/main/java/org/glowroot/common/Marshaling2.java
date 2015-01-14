/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.common;

import java.io.IOException;
import java.io.StringWriter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Throwables;
import org.immutables.common.marshal.Marshaler;
import org.immutables.common.marshal.Marshaling;

public class Marshaling2 {

    private static final JsonFactory jsonFactory = new JsonFactory();

    private Marshaling2() {}

    public static String toJson(Object object) {
        Marshaler<Object> marshaler = Marshaling.marshalerFor(object.getClass());
        try {
            StringWriter writer = new StringWriter();
            JsonGenerator generator = jsonFactory.createGenerator(writer);
            marshaler.marshalInstance(generator, object);
            generator.close();
            return writer.toString();
        } catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }

    public static <T extends /*@NonNull*/Object> String toJson(Iterable<T> object,
            Class<T> expectedType) {
        Marshaler<T> marshaler = Marshaling.marshalerFor(expectedType);
        try {
            StringWriter writer = new StringWriter();
            JsonGenerator generator = jsonFactory.createGenerator(writer);
            marshaler.marshalIterable(generator, object);
            generator.close();
            return writer.toString();
        } catch (IOException ex) {
            throw Throwables.propagate(ex);
        }
    }
}
