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

import checkers.nullness.quals.Nullable;

import com.google.common.collect.ObjectArrays;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutMessageSupplier extends MessageSupplier {

    private final String template;
    private final String[] args;
    private volatile boolean hasReturnValue;
    @Nullable
    private volatile Object returnValue;

    public static PointcutMessageSupplier create(String template, String... args) {
        return new PointcutMessageSupplier(template, args);
    }

    public static PointcutMessageSupplier create(String template, Object... args) {
        // it is safer to convert args to strings immediately in case the object's string can change
        String[] convertedArgs = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            convertedArgs[i] = String.valueOf(args[i]);
        }
        return new PointcutMessageSupplier(template, convertedArgs);
    }

    private PointcutMessageSupplier(String template, String[] args) {
        this.template = template;
        this.args = args;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        hasReturnValue = true;
    }

    @Override
    public Message get() {
        if (hasReturnValue) {
            String[] messageArgs = ObjectArrays.concat(args, String.valueOf(returnValue));
            return Message.from(template + " => {}", messageArgs);
        } else {
            return Message.from(template, args);
        }
    }
}
