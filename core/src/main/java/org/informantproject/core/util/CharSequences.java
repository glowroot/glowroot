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
import java.io.Writer;
import java.util.List;

import org.informantproject.api.LargeCharSequence;
import org.informantproject.api.LargeStringBuilder;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CharSequences {

    public static CharSequence toJson(CharSequence csq) {
        if (csq instanceof LargeCharSequence) {
            LargeStringBuilder sb = new LargeStringBuilder();
            for (CharSequence subSequence : ((LargeCharSequence) csq).getSubSequences()) {
                CharSequence jsonCsq = toJson(subSequence);
                sb.append(jsonCsq);
            }
            return sb.build();
        }
        int escapedSize = JsonCharSequence.escapedSize(csq);
        if (escapedSize == csq.length()) {
            return csq;
        } else {
            return new JsonCharSequence(csq, escapedSize);
        }
    }

    public static void write(CharSequence csq, Writer out) throws IOException {
        if (csq instanceof LargeCharSequence) {
            for (CharSequence subSequence : ((LargeCharSequence) csq).getSubSequences()) {
                write(subSequence, out);
            }
        } else {
            out.write(csq.toString());
        }
    }

    public static List<CharSequence> expandLargeCharSequences(CharSequence csq) {
        List<CharSequence> csqs = Lists.newArrayList();
        expandLargeCharSequences(csq, csqs);
        return csqs;
    }

    private static void expandLargeCharSequences(CharSequence csq, List<CharSequence> csqs) {
        if (csq instanceof LargeCharSequence) {
            for (CharSequence subSequence : ((LargeCharSequence) csq).getSubSequences()) {
                expandLargeCharSequences(subSequence, csqs);
            }
        } else {
            csqs.add(csq);
        }
    }
}
