/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import javax.annotation.Nullable;

import org.glowroot.api.OptionalReturn;
import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindMethodMeta;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindOptionalReturn;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindParameterArray;
import org.glowroot.api.weaving.BindReceiver;
import org.glowroot.api.weaving.BindReturn;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.api.weaving.Mixin;
import org.glowroot.api.weaving.MixinInit;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

public class SomeAspect {

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1|execute2",
            metricName = "xyz")
    public static class BasicAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
        public static void enable() {
            SomeAspectThreadLocals.enabled.set(true);
        }
        public static void disable() {
            SomeAspectThreadLocals.enabled.set(false);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.SuperBasicMisc", methodName = "superBasic",
            metricName = "superbasic")
    public static class SuperBasicAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "java.lang.Throwable", methodName = "toString",
            metricName = "throwable to string")
    public static class ThrowableToStringAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.BasicMisc", methodName = "<init>")
    public static class BasicMiscConstructorAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.BasicMisc", methodName = "<init>",
            methodParameterTypes = {".."})
    public static class BasicMiscAllConstructorAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.orderedEvents.get().add("isEnabled");
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.orderedEvents.get().add("onBefore");
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.orderedEvents.get().add("onReturn");
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.orderedEvents.get().add("onThrow");
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.orderedEvents.get().add("onAfter");
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.BasicMisc", methodName = "withInnerArg",
            methodParameterTypes = {"org.glowroot.weaving.BasicMisc$Inner"})
    public static class BasicWithInnerClassArgAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.BasicMisc$InnerMisc", methodName = "execute1")
    public static class BasicWithInnerClassAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            SomeAspectThreadLocals.enabledCount.increment();
            return SomeAspectThreadLocals.enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindReceiverAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Misc receiver) {
            SomeAspectThreadLocals.isEnabledReceiver.set(receiver);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindReceiver Misc receiver) {
            SomeAspectThreadLocals.onBeforeReceiver.set(receiver);
        }
        @OnReturn
        public static void onReturn(@BindReceiver Misc receiver) {
            SomeAspectThreadLocals.onReturnReceiver.set(receiver);
        }
        @OnThrow
        public static void onThrow(@BindReceiver Misc receiver) {
            SomeAspectThreadLocals.onThrowReceiver.set(receiver);
        }
        @OnAfter
        public static void onAfter(@BindReceiver Misc receiver) {
            SomeAspectThreadLocals.onAfterReceiver.set(receiver);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int"})
    public static class BindParameterAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter String one, @BindParameter int two) {
            SomeAspectThreadLocals.isEnabledParams.set(new Object[] {one, two});
            return true;
        }
        @OnBefore
        public static void onBefore(@BindParameter String one, @BindParameter int two) {
            SomeAspectThreadLocals.onBeforeParams.set(new Object[] {one, two});
        }
        @OnReturn
        public static void onReturn(@BindParameter String one, @BindParameter int two) {
            SomeAspectThreadLocals.onReturnParams.set(new Object[] {one, two});
        }
        @OnThrow
        public static void onThrow(@BindParameter String one, @BindParameter int two) {
            SomeAspectThreadLocals.onThrowParams.set(new Object[] {one, two});
        }
        @OnAfter
        public static void onAfter(@BindParameter String one, @BindParameter int two) {
            SomeAspectThreadLocals.onAfterParams.set(new Object[] {one, two});
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int"})
    public static class BindParameterArrayAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameterArray Object[] args) {
            SomeAspectThreadLocals.isEnabledParams.set(args);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindParameterArray Object[] args) {
            SomeAspectThreadLocals.onBeforeParams.set(args);
        }
        @OnReturn
        public static void onReturn(@BindParameterArray Object[] args) {
            SomeAspectThreadLocals.onReturnParams.set(args);
        }
        @OnThrow
        public static void onThrow(@BindParameterArray Object[] args) {
            SomeAspectThreadLocals.onThrowParams.set(args);
        }
        @OnAfter
        public static void onAfter(@BindParameterArray Object[] args) {
            SomeAspectThreadLocals.onAfterParams.set(args);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindTravelerAdvice {
        @OnBefore
        public static String onBefore() {
            return "a traveler";
        }
        @OnReturn
        public static void onReturn(@BindTraveler String traveler) {
            SomeAspectThreadLocals.onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler String traveler) {
            SomeAspectThreadLocals.onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler String traveler) {
            SomeAspectThreadLocals.onAfterTraveler.set(traveler);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveTravelerAdvice {
        @OnBefore
        public static int onBefore() {
            return 3;
        }
        @OnReturn
        public static void onReturn(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onAfterTraveler.set(traveler);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveBooleanTravelerAdvice {
        @OnBefore
        public static boolean onBefore() {
            return true;
        }
        @OnReturn
        public static void onReturn(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onAfterTraveler.set(traveler);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveTravelerBadAdvice {
        @OnBefore
        public static void onBefore() {}
        @OnReturn
        public static void onReturn(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler int traveler) {
            SomeAspectThreadLocals.onAfterTraveler.set(traveler);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveBooleanTravelerBadAdvice {
        @OnBefore
        public static void onBefore() {}
        @OnReturn
        public static void onReturn(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler boolean traveler) {
            SomeAspectThreadLocals.onAfterTraveler.set(traveler);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindClassMetaAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindClassMeta TestClassMeta meta) {
            SomeAspectThreadLocals.isEnabledClassMeta.set(meta);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindClassMeta TestClassMeta meta) {
            SomeAspectThreadLocals.onBeforeClassMeta.set(meta);
        }
        @OnReturn
        public static void onReturn(@BindClassMeta TestClassMeta meta) {
            SomeAspectThreadLocals.onReturnClassMeta.set(meta);
        }
        @OnThrow
        public static void onThrow(@BindClassMeta TestClassMeta meta) {
            SomeAspectThreadLocals.onThrowClassMeta.set(meta);
        }
        @OnAfter
        public static void onAfter(@BindClassMeta TestClassMeta meta) {
            SomeAspectThreadLocals.onAfterClassMeta.set(meta);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {".."})
    public static class BindMethodMetaAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.isEnabledMethodMeta.set(meta);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onBeforeMethodMeta.set(meta);
        }
        @OnReturn
        public static void onReturn(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onReturnMethodMeta.set(meta);
        }
        @OnThrow
        public static void onThrow(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onThrowMethodMeta.set(meta);
        }
        @OnAfter
        public static void onAfter(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onAfterMethodMeta.set(meta);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.other.ArrayMisc", methodName = "executeArray",
            methodParameterTypes = {".."})
    public static class BindMethodMetaArrayAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.isEnabledMethodMeta.set(meta);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onBeforeMethodMeta.set(meta);
        }
        @OnReturn
        public static void onReturn(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onReturnMethodMeta.set(meta);
        }
        @OnThrow
        public static void onThrow(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onThrowMethodMeta.set(meta);
        }
        @OnAfter
        public static void onAfter(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onAfterMethodMeta.set(meta);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.other.ArrayMisc",
            methodName = "executeWithArrayReturn", methodParameterTypes = {".."})
    public static class BindMethodMetaReturnArrayAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.isEnabledMethodMeta.set(meta);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onBeforeMethodMeta.set(meta);
        }
        @OnReturn
        public static void onReturn(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onReturnMethodMeta.set(meta);
        }
        @OnThrow
        public static void onThrow(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onThrowMethodMeta.set(meta);
        }
        @OnAfter
        public static void onAfter(@BindMethodMeta TestMethodMeta meta) {
            SomeAspectThreadLocals.onAfterMethodMeta.set(meta);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class BindReturnAdvice {
        @OnReturn
        public static void onReturn(@BindReturn String value) {
            SomeAspectThreadLocals.returnValue.set(value);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindPrimitiveReturnAdvice {
        @OnReturn
        public static void onReturn(@BindReturn int value) {
            SomeAspectThreadLocals.returnValue.set(value);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindAutoboxedReturnAdvice {
        @OnReturn
        public static void onReturn(@BindReturn Object value) {
            SomeAspectThreadLocals.returnValue.set(value);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class BindOptionalReturnAdvice {
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            SomeAspectThreadLocals.optionalReturnValue.set(optionalReturn);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindOptionalVoidReturnAdvice {
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            SomeAspectThreadLocals.optionalReturnValue.set(optionalReturn);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindOptionalPrimitiveReturnAdvice {
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            SomeAspectThreadLocals.optionalReturnValue.set(optionalReturn);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindThrowableAdvice {
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t) {
            SomeAspectThreadLocals.throwable.set(t);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            metricName = "efg")
    public static class BindMethodNameAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindMethodName String methodName) {
            SomeAspectThreadLocals.isEnabledMethodName.set(methodName);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodName String methodName) {
            SomeAspectThreadLocals.onBeforeMethodName.set(methodName);
        }
        @OnReturn
        public static void onReturn(@BindMethodName String methodName) {
            SomeAspectThreadLocals.onReturnMethodName.set(methodName);
        }
        @OnThrow
        public static void onThrow(@BindMethodName String methodName) {
            SomeAspectThreadLocals.onThrowMethodName.set(methodName);
        }
        @OnAfter
        public static void onAfter(@BindMethodName String methodName) {
            SomeAspectThreadLocals.onAfterMethodName.set(methodName);
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class ChangeReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnReturn
        public static String onReturn(@BindReturn String value, @BindMethodName String methodName) {
            return "modified " + value + ":" + methodName;
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {".."})
    public static class MethodParametersDotDotAdvice1 {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", ".."})
    public static class MethodParametersDotDotAdvice2 {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int", ".."})
    public static class MethodParametersDotDotAdvice3 {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    public interface HasString {
        String getString();
        void setString(String string);
    }

    @Mixin(target = "org.glowroot.weaving.BasicMisc")
    public static class HasStringClassMixin implements HasString {
        private String string;
        @MixinInit
        private void initHasString() {
            if (string == null) {
                string = "a string";
            } else {
                string = "init called twice";
            }
        }
        @Override
        public String getString() {
            return string;
        }
        @Override
        public void setString(String string) {
            this.string = string;
        }
    }

    @Mixin(target = "org.glowroot.weaving.Misc")
    public static class HasStringInterfaceMixin implements HasString {
        private String string;
        @MixinInit
        private void initHasString() {
            string = "a string";
        }
        @Override
        public String getString() {
            return string;
        }
        @Override
        public void setString(String string) {
            this.string = string;
        }
    }

    @Mixin(target = {"org.glowroot.weaving.Misc", "org.glowroot.weaving.Misc2"})
    public static class HasStringMultipleMixin implements HasString {
        private String string;
        @MixinInit
        private void initHasString() {
            string = "a string";
        }
        @Override
        public String getString() {
            return string;
        }
        @Override
        public void setString(String string) {
            this.string = string;
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            ignoreSelfNested = true)
    public static class NotNestingAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            ignoreSelfNested = true)
    public static class NotNestingWithNoIsEnabledAdvice {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute*",
            methodParameterTypes = {".."}, metricName = "abc xyz")
    public static class InnerMethodAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute*",
            methodParameterTypes = {".."}, ignoreSelfNested = true)
    public static class MultipleMethodsAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.StaticMisc", methodName = "executeStatic")
    public static class StaticAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodModifiers = MethodModifier.STATIC)
    public static class NonMatchingStaticAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Mis*", methodName = "execute1")
    public static class ClassNamePatternAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodReturnType = "void")
    public static class MethodReturnVoidAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturnType = "java.lang.CharSequence")
    public static class MethodReturnCharSequenceAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturnType = "java.lang.String")
    public static class MethodReturnStringAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodReturnType = "java.lang.String")
    public static class NonMatchingMethodReturnAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturnType = "java.lang.Number")
    public static class NonMatchingMethodReturnAdvice2 extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturnType = "java.lang.")
    public static class MethodReturnNarrowingAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "*",
            methodParameterTypes = {".."}, metricName = "wild")
    public static class WildMethodAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodParameterTypes = {"int", "double", "long", "byte[]"})
    public static class PrimitiveAdvice extends BasicAdvice {}

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodParameterTypes = {"int", "double", "*", ".."})
    public static class PrimitiveWithWildcardAdvice {
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindParameter int x) {
            SomeAspectThreadLocals.enabledCount.increment();
            return true;
        }
        @OnBefore
        public static void onBefore(@SuppressWarnings("unused") @BindParameter int x) {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodParameterTypes = {"int", "double", "*", ".."})
    public static class PrimitiveWithAutoboxAdvice {
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindParameter Object x) {
            SomeAspectThreadLocals.enabledCount.increment();
            return true;
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int"})
    public static class BrokenAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnBefore
        public static @Nullable Object onBefore() {
            return null;
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindTraveler Object traveler) {}
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int"})
    public static class VeryBadAdvice {
        @OnBefore
        public static Object onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {"java.lang.String", "int"})
    public static class MoreVeryBadAdvice {
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    // same as MoreVeryBadAdvice, but testing weaving a method with a non-void return type
    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class MoreVeryBadAdvice2 {
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc3", methodName = "identity",
            methodParameterTypes = {"org.glowroot.weaving.BasicMisc"})
    public static class CircularClassDependencyAdvice {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class InterfaceAppearsTwiceInHierarchyAdvice {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    @Pointcut(className = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodParameterTypes = {".."})
    public static class FinalMethodAdvice {
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
    }

    // test weaving against JSR bytecode that ends up being inlined via JSRInlinerAdapter
    @Pointcut(className = "org.apache.jackrabbit.core.persistence.pool.BundleDbPersistenceManager",
            methodName = "loadBundle",
            methodParameterTypes = {"org.apache.jackrabbit.core.id.NodeId"})
    public static class TestJSRMethodAdvice {}

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice {}

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice2 {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
    }

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice3 {
        @OnBefore
        public static void onBefore() {}
    }

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice4 {
        @OnReturn
        public static void onReturn() {}
    }

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice5 {
        @OnThrow
        public static void onThrow() {}
    }

    // test weaving against 1.7 bytecode with stack frames
    @Pointcut(className = "org.xnio.Buffers", methodName = "*",
            methodParameterTypes = {".."})
    public static class TestBytecodeWithStackFramesAdvice6 {
        @OnAfter
        public static void onAfter() {}
    }

    public static class TestClassMeta {

        private final Class<?> clazz;

        public TestClassMeta(Class<?> clazz) {
            this.clazz = clazz;
        }

        public String getClazzName() {
            return clazz.getName();
        }
    }

    public static class TestMethodMeta {

        private final Class<?> declaringClass;
        private final Class<?> returnType;
        private final Class<?>[] parameterTypes;

        public TestMethodMeta(Class<?> declaringClass, Class<?> returnType,
                Class<?>... parameterTypes) {
            this.declaringClass = declaringClass;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
        }

        public String getDeclaringClassName() {
            return declaringClass.getName();
        }

        public String getReturnTypeName() {
            return returnType.getName();
        }

        public String[] getParameterTypeNames() {
            String[] parameterTypeNames = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                parameterTypeNames[i] = parameterTypes[i].getName();
            }
            return parameterTypeNames;
        }
    }
}
