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
package org.informantproject.api;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@NotThreadSafe
public interface Span extends Timer {

    void end();

    // only marks trace as error if this is the root span
    void endWithError(ErrorMessage errorMessage);

    // TODO revisit this, what about setMessage(MessageSupplier) instead?
    void updateMessage(MessageUpdater updater);

    public interface MessageUpdater {
        void update(MessageSupplier messageSupplier);
    }
}
