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
import org.glowroot.api.weaving.BindMethodArg;
import org.glowroot.api.weaving.BindMethodArgArray;
import org.glowroot.api.weaving.BindMethodName;
import org.glowroot.api.weaving.BindOptionalReturn;
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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SomeAspect {

    public static final ThreadLocal<Boolean> enabled = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return true;
        }
    };
    public static final IntegerThreadLocal enabledCount = new IntegerThreadLocal();
    public static final IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
    public static final IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
    public static final IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
    public static final IntegerThreadLocal onAfterCount = new IntegerThreadLocal();

    public static void resetThreadLocals() {
        enabled.set(true);
        enabledCount.set(0);
        onBeforeCount.set(0);
        onReturnCount.set(0);
        onThrowCount.set(0);
        onAfterCount.set(0);
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1|execute2",
            metricName = "xyz")
    public static class BasicAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
        public static void enable() {
            enabled.set(true);
        }
        public static void disable() {
            enabled.set(false);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.BasicMisc", methodName = "hashCode",
            metricName = "hashcode")
    public static class HashCodeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    @Pointcut(typeName = "java.lang.Exception", methodName = "toString",
            metricName = "etostring")
    public static class ExceptionToStringAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    // note: constructor pointcuts do not currently support @OnBefore
    @Pointcut(typeName = "org.glowroot.weaving.BasicMisc", methodName = "<init>")
    public static class BasicMiscConstructorAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    // note: constructor pointcuts do not currently support @OnBefore
    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "<init>")
    public static class BasicMiscConstructorOnInterfaceImplAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.BasicMisc", methodName = "withInnerArg",
            methodArgs = {"org.glowroot.weaving.BasicMisc$Inner"})
    public static class BasicWithInnerClassArgAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.BasicMisc$InnerMisc", methodName = "execute1")
    public static class BasicWithInnerClassAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindReceiverAdvice {
        public static final ThreadLocal<Misc> isEnabledReceiver = new ThreadLocal<Misc>();
        public static final ThreadLocal<Misc> onBeforeReceiver = new ThreadLocal<Misc>();
        public static final ThreadLocal<Misc> onReturnReceiver = new ThreadLocal<Misc>();
        public static final ThreadLocal<Misc> onThrowReceiver = new ThreadLocal<Misc>();
        public static final ThreadLocal<Misc> onAfterReceiver = new ThreadLocal<Misc>();
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Misc receiver) {
            isEnabledReceiver.set(receiver);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindReceiver Misc receiver) {
            onBeforeReceiver.set(receiver);
        }
        @OnReturn
        public static void onReturn(@BindReceiver Misc receiver) {
            onReturnReceiver.set(receiver);
        }
        @OnThrow
        public static void onThrow(@BindReceiver Misc receiver) {
            onThrowReceiver.set(receiver);
        }
        @OnAfter
        public static void onAfter(@BindReceiver Misc receiver) {
            onAfterReceiver.set(receiver);
        }
        public static void resetThreadLocals() {
            isEnabledReceiver.remove();
            onBeforeReceiver.remove();
            onReturnReceiver.remove();
            onThrowReceiver.remove();
            onAfterReceiver.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int"})
    public static class BindMethodArgAdvice {
        public static final ThreadLocal<Object[]> isEnabledParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onBeforeParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onReturnParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onThrowParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onAfterParams = new ThreadLocal<Object[]>();
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg String one, @BindMethodArg int two) {
            isEnabledParams.set(new Object[] {one, two});
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodArg String one, @BindMethodArg int two) {
            onBeforeParams.set(new Object[] {one, two});
        }
        @OnReturn
        public static void onReturn(@BindMethodArg String one, @BindMethodArg int two) {
            onReturnParams.set(new Object[] {one, two});
        }
        @OnThrow
        public static void onThrow(@BindMethodArg String one, @BindMethodArg int two) {
            onThrowParams.set(new Object[] {one, two});
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String one, @BindMethodArg int two) {
            onAfterParams.set(new Object[] {one, two});
        }
        public static void resetThreadLocals() {
            isEnabledParams.remove();
            onBeforeParams.remove();
            onReturnParams.remove();
            onThrowParams.remove();
            onAfterParams.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int"})
    public static class BindMethodArgArrayAdvice {
        public static final ThreadLocal<Object[]> isEnabledParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onBeforeParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onReturnParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onThrowParams = new ThreadLocal<Object[]>();
        public static final ThreadLocal<Object[]> onAfterParams = new ThreadLocal<Object[]>();
        @IsEnabled
        public static boolean isEnabled(@BindMethodArgArray Object[] args) {
            isEnabledParams.set(args);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodArgArray Object[] args) {
            onBeforeParams.set(args);
        }
        @OnReturn
        public static void onReturn(@BindMethodArgArray Object[] args) {
            onReturnParams.set(args);
        }
        @OnThrow
        public static void onThrow(@BindMethodArgArray Object[] args) {
            onThrowParams.set(args);
        }
        @OnAfter
        public static void onAfter(@BindMethodArgArray Object[] args) {
            onAfterParams.set(args);
        }
        public static void resetThreadLocals() {
            isEnabledParams.remove();
            onBeforeParams.remove();
            onReturnParams.remove();
            onThrowParams.remove();
            onAfterParams.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindTravelerAdvice {
        public static final ThreadLocal<String> onReturnTraveler = new ThreadLocal<String>();
        public static final ThreadLocal<String> onThrowTraveler = new ThreadLocal<String>();
        public static final ThreadLocal<String> onAfterTraveler = new ThreadLocal<String>();
        @OnBefore
        public static String onBefore() {
            return "a traveler";
        }
        @OnReturn
        public static void onReturn(@BindTraveler String traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler String traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler String traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.remove();
            onThrowTraveler.remove();
            onAfterTraveler.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveTravelerAdvice {
        public static final ThreadLocal<Integer> onReturnTraveler = new ThreadLocal<Integer>();
        public static final ThreadLocal<Integer> onThrowTraveler = new ThreadLocal<Integer>();
        public static final ThreadLocal<Integer> onAfterTraveler = new ThreadLocal<Integer>();
        @OnBefore
        public static int onBefore() {
            return 3;
        }
        @OnReturn
        public static void onReturn(@BindTraveler int traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler int traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler int traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.remove();
            onThrowTraveler.remove();
            onAfterTraveler.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveBooleanTravelerAdvice {
        public static final ThreadLocal<Boolean> onReturnTraveler = new ThreadLocal<Boolean>();
        public static final ThreadLocal<Boolean> onThrowTraveler = new ThreadLocal<Boolean>();
        public static final ThreadLocal<Boolean> onAfterTraveler = new ThreadLocal<Boolean>();
        @OnBefore
        public static boolean onBefore() {
            return true;
        }
        @OnReturn
        public static void onReturn(@BindTraveler boolean traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler boolean traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler boolean traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.remove();
            onThrowTraveler.remove();
            onAfterTraveler.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveTravelerBadAdvice {
        public static final ThreadLocal<Integer> onReturnTraveler = new ThreadLocal<Integer>();
        public static final ThreadLocal<Integer> onThrowTraveler = new ThreadLocal<Integer>();
        public static final ThreadLocal<Integer> onAfterTraveler = new ThreadLocal<Integer>();
        @OnBefore
        public static void onBefore() {}
        @OnReturn
        public static void onReturn(@BindTraveler int traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler int traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler int traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.remove();
            onThrowTraveler.remove();
            onAfterTraveler.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveBooleanTravelerBadAdvice {
        public static final ThreadLocal<Boolean> onReturnTraveler = new ThreadLocal<Boolean>();
        public static final ThreadLocal<Boolean> onThrowTraveler = new ThreadLocal<Boolean>();
        public static final ThreadLocal<Boolean> onAfterTraveler = new ThreadLocal<Boolean>();
        @OnBefore
        public static void onBefore() {}
        @OnReturn
        public static void onReturn(@BindTraveler boolean traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@BindTraveler boolean traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@BindTraveler boolean traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.remove();
            onThrowTraveler.remove();
            onAfterTraveler.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class BindReturnAdvice {
        public static final ThreadLocal<String> returnValue = new ThreadLocal<String>();
        @OnReturn
        public static void onReturn(@BindReturn String value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindPrimitiveReturnAdvice {
        public static final ThreadLocal<Integer> returnValue = new ThreadLocal<Integer>();
        @OnReturn
        public static void onReturn(@BindReturn int value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindAutoboxedReturnAdvice {
        public static final ThreadLocal<Object> returnValue = new ThreadLocal<Object>();
        @OnReturn
        public static void onReturn(@BindReturn Object value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class BindOptionalReturnAdvice {
        public static final ThreadLocal<OptionalReturn> returnValue =
                new ThreadLocal<OptionalReturn>();
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            returnValue.set(optionalReturn);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindOptionalVoidReturnAdvice {
        public static final ThreadLocal<OptionalReturn> returnValue =
                new ThreadLocal<OptionalReturn>();
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            returnValue.set(optionalReturn);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc", methodName = "executeWithIntReturn")
    public static class BindOptionalPrimitiveReturnAdvice {
        public static final ThreadLocal<OptionalReturn> returnValue =
                new ThreadLocal<OptionalReturn>();
        @OnReturn
        public static void onReturn(@BindOptionalReturn OptionalReturn optionalReturn) {
            returnValue.set(optionalReturn);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class BindThrowableAdvice {
        public static final ThreadLocal<Throwable> throwable = new ThreadLocal<Throwable>();
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t) {
            throwable.set(t);
        }
        public static void resetThreadLocals() {
            throwable.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            metricName = "efg")
    public static class BindMethodNameAdvice {
        public static final ThreadLocal<String> isEnabledMethodName = new ThreadLocal<String>();
        public static final ThreadLocal<String> onBeforeMethodName = new ThreadLocal<String>();
        public static final ThreadLocal<String> onReturnMethodName = new ThreadLocal<String>();
        public static final ThreadLocal<String> onThrowMethodName = new ThreadLocal<String>();
        public static final ThreadLocal<String> onAfterMethodName = new ThreadLocal<String>();
        @IsEnabled
        public static boolean isEnabled(@BindMethodName String methodName) {
            isEnabledMethodName.set(methodName);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodName String methodName) {
            onBeforeMethodName.set(methodName);
        }
        @OnReturn
        public static void onReturn(@BindMethodName String methodName) {
            onReturnMethodName.set(methodName);
        }
        @OnThrow
        public static void onThrow(@BindMethodName String methodName) {
            onThrowMethodName.set(methodName);
        }
        @OnAfter
        public static void onAfter(@BindMethodName String methodName) {
            onAfterMethodName.set(methodName);
        }
        public static void resetThreadLocals() {
            isEnabledMethodName.remove();
            onBeforeMethodName.remove();
            onReturnMethodName.remove();
            onThrowMethodName.remove();
            onAfterMethodName.remove();
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class ChangeReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnReturn
        public static String onReturn(@BindReturn String value) {
            return "modified " + value;
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {".."})
    public static class MethodArgsDotDotAdvice1 {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", ".."})
    public static class MethodArgsDotDotAdvice2 {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int", ".."})
    public static class MethodArgsDotDotAdvice3 {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
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

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            ignoreSameNested = true)
    public static class NotNestingAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            ignoreSameNested = true)
    public static class NotNestingWithNoIsEnabledAdvice {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            onAfterCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute*",
            methodArgs = {".."}, metricName = "abc xyz")
    public static class InnerMethodAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute*",
            methodArgs = {".."}, ignoreSameNested = true)
    public static class MultipleMethodsAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.StaticMisc", methodName = "executeStatic")
    public static class StaticAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodModifiers = MethodModifier.STATIC)
    public static class NonMatchingStaticAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Mis*", methodName = "execute1")
    public static class TypeNamePatternAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodReturn = "void")
    public static class MethodReturnVoidAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.CharSequence")
    public static class MethodReturnCharSequenceAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.String")
    public static class MethodReturnStringAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1",
            methodReturn = "java.lang.String")
    public static class NonMatchingMethodReturnAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.Number")
    public static class NonMatchingMethodReturnAdvice2 extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.")
    public static class MethodReturnNarrowingAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "*",
            methodArgs = {".."}, metricName = "wild")
    public static class WildMethodAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = {"int", "double", "long", "byte[]"})
    public static class PrimitiveAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = {"int", "double", "*", ".."})
    public static class PrimitiveWithWildcardAdvice {
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindMethodArg int x) {
            enabledCount.increment();
            return true;
        }
        @OnBefore
        public static void onBefore(@SuppressWarnings("unused") @BindMethodArg int x) {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = {"int", "double", "*", ".."})
    public static class PrimitiveWithAutoboxAdvice {
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindMethodArg Object x) {
            enabledCount.increment();
            return true;
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int"})
    public static class BrokenAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnBefore
        @Nullable
        public static Object onBefore() {
            return null;
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @BindTraveler Object traveler) {}
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int"})
    public static class VeryBadAdvice {
        @OnBefore
        public static Object onBefore() {
            onBeforeCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            onAfterCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = {"java.lang.String", "int"})
    public static class MoreVeryBadAdvice {
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            onAfterCount.increment();
        }
        public static void resetThreadLocals() {
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
    }

    // same as MoreVeryBadAdvice, but testing weaving a method with a non-void return type
    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "executeWithReturn")
    public static class MoreVeryBadAdvice2 {
        @OnReturn
        public static void onReturn() {
            onReturnCount.increment();
            throw new IllegalStateException("Sorry");
        }
        @OnThrow
        public static void onThrow() {
            // should not get called
            onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            // should not get called
            onAfterCount.increment();
        }
        public static void resetThreadLocals() {
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc3", methodName = "identity",
            methodArgs = {"org.glowroot.weaving.BasicMisc"})
    public static class CircularClassDependencyAdvice {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "org.glowroot.weaving.Misc", methodName = "execute1")
    public static class InterfaceAppearsTwiceInHierarchyAdvice {
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    // test weaving against JSR bytecode that ends up being inlined via JSRInlinerAdapter
    @Pointcut(typeName = "org.apache.jackrabbit.core.persistence.pool.BundleDbPersistenceManager",
            methodName = "loadBundle", methodArgs = {"org.apache.jackrabbit.core.id.NodeId"})
    public static class TestJSRInlinedMethodAdvice {}

    static class IntegerThreadLocal extends ThreadLocal<Integer> {
        @Override
        protected Integer initialValue() {
            return 0;
        }
        private void increment() {
            set(get() + 1);
        }
    }
}
