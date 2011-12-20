/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.trace;

import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.util.Clock;

import com.google.common.base.Ticker;
import com.google.inject.Inject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceTestData {

    private final Clock clock;
    private final Ticker ticker;

    @Inject
    public TraceTestData(Clock clock, Ticker ticker) {
        this.clock = clock;
        this.ticker = ticker;
    }

    public Trace createTrace() {
        RootSpanDetail spanDetail = new RootSpanDetail() {
            public String getDescription() {
                return "Level One";
            }
            public SpanContextMap getContextMap() {
                SpanContextMap contextMap = SpanContextMap.of("arg1", "a", "arg2", "b");
                SpanContextMap nestedContextMap = SpanContextMap.of("nestedkey11", "a",
                        "nestedkey12", "b", "subnestedkey1",
                        SpanContextMap.of("subnestedkey1", "a", "subnestedkey2", "b"));
                contextMap.put("nested1", nestedContextMap);
                contextMap.put("nested2",
                        SpanContextMap.of("nestedkey21", "a", "nestedkey22", "b"));
                return contextMap;
            }
            public String getUsername() {
                return null;
            }
        };

        Trace trace = new Trace(spanDetail, clock, ticker);

        Span secondSpan = trace.getRootSpan().pushSpan(new SpanDetail() {
            public String getDescription() {
                return "Level Two";
            }
            public SpanContextMap getContextMap() {
                return SpanContextMap.of("arg1", "ax", "arg2", "bx");
            }
        });

        Span thirdSpan = trace.getRootSpan().pushSpan(new SpanDetail() {
            public String getDescription() {
                return "Level Three";
            }
            public SpanContextMap getContextMap() {
                return SpanContextMap.of("arg1", "axy", "arg2", "bxy");
            }
        });

        trace.getRootSpan().popSpan(thirdSpan, ticker.read());
        trace.getRootSpan().popSpan(secondSpan, ticker.read());
        trace.getRootSpan().popSpan(trace.getRootSpan().getRootSpan(), ticker.read());
        return trace;
    }
}
