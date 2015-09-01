/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.common.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public abstract class Traverser<T extends /*@NonNull*/Object, E extends Exception> {

    private static final Object ALREADY_TRAVERSED_MARKER = new Object();

    private final Deque<Object> stack;

    public Traverser(T root) {
        stack = new ArrayDeque<Object>();
        stack.push(root);
    }

    @SuppressWarnings("unchecked")
    public void traverse() throws E {
        while (!stack.isEmpty()) {
            Object popped = stack.pop();
            if (popped == ALREADY_TRAVERSED_MARKER) {
                revisitAfterChildren((T) stack.pop());
                continue;
            }
            T unprocessed = (T) popped;
            Iterator<? extends T> childNodes = visit(unprocessed).iterator();
            if (!childNodes.hasNext()) {
                // optimization for no children
                revisitAfterChildren(unprocessed);
            } else {
                stack.push(unprocessed);
                stack.push(ALREADY_TRAVERSED_MARKER);
                while (childNodes.hasNext()) {
                    stack.push(childNodes.next());
                }
            }
        }
    }

    public abstract Iterable<? extends T> visit(T node) throws E;

    public void revisitAfterChildren(@SuppressWarnings("unused") T node) throws E {};
}
