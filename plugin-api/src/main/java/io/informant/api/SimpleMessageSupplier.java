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
public class SimpleMessageSupplier extends MessageSupplier {

    private final String template;
    private final/*@Nullable*/String[] args;
    private volatile boolean hasReturnValue;
    @Nullable
    private volatile Object returnValue;

    // supplier creation needs to be as efficient as possible
    public static SimpleMessageSupplier create(String template, @Nullable String... args) {
        return new SimpleMessageSupplier(template, args);
    }

    // supplier creation needs to be as efficient as possible
    public static SimpleMessageSupplier create(String template, @Nullable Object... args) {
        // it is safer however to convert args to strings immediately in case any of the object's
        // string representations can change
        return new SimpleMessageSupplier(template, convert(args));
    }

    private SimpleMessageSupplier(String template, @Nullable String[] args) {
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
            /*@Nullable*/String[] messageArgs =
                    ObjectArrays.concat(args, String.valueOf(returnValue));
            return Message.from(template + " => {}", messageArgs);
        } else {
            return Message.from(template, args);
        }
    }

    @Nullable
    private static String[] convert(@Nullable Object... args) {
        /*@Nullable*/String[] convertedArgs = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                convertedArgs[i] = null;
            } else {
                try {
                    convertedArgs[i] = arg.toString();
                } catch (Throwable t) {
                    // just in case an exception is thrown in toString()
                    convertedArgs[i] = "<an error occurred calling toString() on " + arg + ">";
                }
            }
        }
        return convertedArgs;
    }
}
