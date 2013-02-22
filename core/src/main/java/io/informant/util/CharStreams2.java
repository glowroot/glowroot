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
package io.informant.util;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.common.io.InputSupplier;

/**
 * Additional method similar to those in Guava's {@link CharStreams} class.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class CharStreams2 {

    private CharStreams2() {}

    // hopefully something like this will be in guava at some point
    // see https://code.google.com/p/guava-libraries/issues/detail?id=1310
    public static CharSource join(List<CharSource> charSources) {
        // wrap CharSources into InputSuppliers in order to use CharStreams.join(InputSuppliers)
        final List<InputSupplier<Reader>> suppliers = Lists.newArrayList();
        for (final CharSource charSource : charSources) {
            suppliers.add(new InputSupplier<Reader>() {
                public Reader getInput() throws IOException {
                    return charSource.openStream();
                }
            });
        }
        return new CharSource() {
            @Override
            public Reader openStream() throws IOException {
                // and then wrap resulting joined InputSupplier back into CharSource
                return CharStreams.join(suppliers).getInput();
            }
        };
    }
}
