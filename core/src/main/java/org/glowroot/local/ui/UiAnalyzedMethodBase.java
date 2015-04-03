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
package org.glowroot.local.ui;

import java.lang.reflect.Modifier;

import javax.annotation.Nullable;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable(prehash = true)
abstract class UiAnalyzedMethodBase {

    public abstract String name();
    // these are class names
    public abstract ImmutableList<String> parameterTypes();
    public abstract String returnType();
    public abstract int modifiers();
    abstract @Nullable String signature();
    abstract ImmutableList<String> exceptions();

    static Ordering<UiAnalyzedMethod> ordering() {
        return UiAnalyzedMethodOrdering.INSTANCE;
    }

    private static class UiAnalyzedMethodOrdering extends Ordering<UiAnalyzedMethod> {

        static final UiAnalyzedMethodOrdering INSTANCE = new UiAnalyzedMethodOrdering();

        @Override
        public int compare(@Nullable UiAnalyzedMethod left, @Nullable UiAnalyzedMethod right) {
            checkNotNull(left);
            checkNotNull(right);
            return ComparisonChain.start()
                    .compare(getAccessibility(left), getAccessibility(right))
                    .compare(left.name(), right.name())
                    .compare(left.parameterTypes().size(), right.parameterTypes().size())
                    .result();
        }

        private static int getAccessibility(UiAnalyzedMethod analyzedMethod) {
            int modifiers = analyzedMethod.modifiers();
            if (Modifier.isPublic(modifiers)) {
                return 1;
            } else if (Modifier.isProtected(modifiers)) {
                return 2;
            } else if (Modifier.isPrivate(modifiers)) {
                return 4;
            } else {
                // package-private
                return 3;
            }
        }
    }
}
