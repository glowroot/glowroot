/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.api;

import java.util.concurrent.TimeUnit;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface Span extends Endable {

    void end();

    void endWithStackTrace(long threshold, TimeUnit unit);

    // only marks trace as error if this is the root span
    void endWithError(ErrorMessage errorMessage);

    // sometimes it's convenient to get the MessageSupplier back from the span, e.g. in @OnReturn
    // to add the return value to the message
    MessageSupplier getMessageSupplier();
}
