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
package io.informant.weaving;

import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindMethodArgArray;
import io.informant.api.weaving.BindMethodName;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.MethodModifier;
import io.informant.api.weaving.Mixin;
import io.informant.api.weaving.MixinInit;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;
import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SomeAspect {

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1|execute2")
    public static class BasicAdvice {
        public static ThreadLocal<Boolean> enabled = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return true;
            }
        };
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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
        public static void resetThreadLocals() {
            enabled.set(true);
            enabledCount.set(0);
            onBeforeCount.set(0);
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
        public static void enable() {
            enabled.set(true);
        }
        public static void disable() {
            enabled.set(false);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.BasicMisc", methodName = "<init>")
    public static class BasicMiscConstructorAdvice {
        public static ThreadLocal<Boolean> enabled = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return true;
            }
        };
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
        @IsEnabled
        public static boolean isEnabled() {
            enabledCount.increment();
            return enabled.get();
        }
        // @OnBefore
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
            enabled.set(true);
            enabledCount.set(0);
            onBeforeCount.set(0);
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
        public static void enable() {
            enabled.set(true);
        }
        public static void disable() {
            enabled.set(false);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.BasicMisc", methodName = "withInnerArg",
            methodArgs = { "io.informant.weaving.BasicMisc.Inner" })
    public static class BasicWithInnerClassArgAdvice {
        public static ThreadLocal<Boolean> enabled = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return true;
            }
        };
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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
        public static void resetThreadLocals() {
            enabled.set(true);
            enabledCount.set(0);
            onBeforeCount.set(0);
            onReturnCount.set(0);
            onThrowCount.set(0);
            onAfterCount.set(0);
        }
        public static void enable() {
            enabled.set(true);
        }
        public static void disable() {
            enabled.set(false);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class BindTargetAdvice {
        public static ThreadLocal<Misc> isEnabledTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onBeforeTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onReturnTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onThrowTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onAfterTarget = new ThreadLocal<Misc>();
        @IsEnabled
        public static boolean isEnabled(@BindTarget Misc target) {
            isEnabledTarget.set(target);
            return true;
        }
        @OnBefore
        public static void onBefore(@BindTarget Misc target) {
            onBeforeTarget.set(target);
        }
        @OnReturn
        public static void onReturn(@BindTarget Misc target) {
            onReturnTarget.set(target);
        }
        @OnThrow
        public static void onThrow(@BindTarget Misc target) {
            onThrowTarget.set(target);
        }
        @OnAfter
        public static void onAfter(@BindTarget Misc target) {
            onAfterTarget.set(target);
        }
        public static void resetThreadLocals() {
            isEnabledTarget.remove();
            onBeforeTarget.remove();
            onReturnTarget.remove();
            onThrowTarget.remove();
            onAfterTarget.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class BindMethodArgAdvice {
        public static ThreadLocal<Object[]> isEnabledParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onBeforeParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onReturnParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onThrowParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onAfterParams = new ThreadLocal<Object[]>();
        @IsEnabled
        public static boolean isEnabled(@BindMethodArg String one, @BindMethodArg int two) {
            isEnabledParams.set(new Object[] { one, two });
            return true;
        }
        @OnBefore
        public static void onBefore(@BindMethodArg String one, @BindMethodArg int two) {
            onBeforeParams.set(new Object[] { one, two });
        }
        @OnReturn
        public static void onReturn(@BindMethodArg String one, @BindMethodArg int two) {
            onReturnParams.set(new Object[] { one, two });
        }
        @OnThrow
        public static void onThrow(@BindMethodArg String one, @BindMethodArg int two) {
            onThrowParams.set(new Object[] { one, two });
        }
        @OnAfter
        public static void onAfter(@BindMethodArg String one, @BindMethodArg int two) {
            onAfterParams.set(new Object[] { one, two });
        }
        public static void resetThreadLocals() {
            isEnabledParams.remove();
            onBeforeParams.remove();
            onReturnParams.remove();
            onThrowParams.remove();
            onAfterParams.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class BindMethodArgArrayAdvice {
        public static ThreadLocal<Object[]> isEnabledParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onBeforeParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onReturnParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onThrowParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onAfterParams = new ThreadLocal<Object[]>();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class BindTravelerAdvice {
        public static ThreadLocal<String> onReturnTraveler = new ThreadLocal<String>();
        public static ThreadLocal<String> onThrowTraveler = new ThreadLocal<String>();
        public static ThreadLocal<String> onAfterTraveler = new ThreadLocal<String>();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveTravelerAdvice {
        public static ThreadLocal<Integer> onReturnTraveler = new ThreadLocal<Integer>();
        public static ThreadLocal<Integer> onThrowTraveler = new ThreadLocal<Integer>();
        public static ThreadLocal<Integer> onAfterTraveler = new ThreadLocal<Integer>();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class BindPrimitiveBooleanTravelerAdvice {
        public static ThreadLocal<Boolean> onReturnTraveler = new ThreadLocal<Boolean>();
        public static ThreadLocal<Boolean> onThrowTraveler = new ThreadLocal<Boolean>();
        public static ThreadLocal<Boolean> onAfterTraveler = new ThreadLocal<Boolean>();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithReturn")
    public static class BindReturnAdvice {
        public static ThreadLocal<String> returnValue = new ThreadLocal<String>();
        @OnReturn
        public static void onReturn(@BindReturn String value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindPrimitiveReturnAdvice {
        public static ThreadLocal<Integer> returnValue = new ThreadLocal<Integer>();
        @OnReturn
        public static void onReturn(@BindReturn int value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.PrimitiveMisc",
            methodName = "executeWithIntReturn")
    public static class BindAutoboxedReturnAdvice {
        public static ThreadLocal<Object> returnValue = new ThreadLocal<Object>();
        @OnReturn
        public static void onReturn(@BindReturn Object value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class BindThrowableAdvice {
        public static ThreadLocal<Throwable> throwable = new ThreadLocal<Throwable>();
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t) {
            throwable.set(t);
        }
        public static void resetThreadLocals() {
            throwable.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            metricName = "efg")
    public static class BindMethodNameAdvice {
        public static ThreadLocal<String> isEnabledMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onBeforeMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onReturnMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onThrowMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onAfterMethodName = new ThreadLocal<String>();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithReturn")
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { ".." })
    public static class MethodArgsDotDotAdvice1 {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", ".." })
    public static class MethodArgsDotDotAdvice2 {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int", ".." })
    public static class MethodArgsDotDotAdvice3 {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
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

    @Mixin(target = "io.informant.weaving.BasicMisc")
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
        public String getString() {
            return string;
        }
        public void setString(String string) {
            this.string = string;
        }
    }

    @Mixin(target = "io.informant.weaving.Misc")
    public static class HasStringInterfaceMixin implements HasString {
        private String string;
        @MixinInit
        private void initHasString() {
            string = "a string";
        }
        public String getString() {
            return string;
        }
        public void setString(String string) {
            this.string = string;
        }
    }

    @Mixin(target = { "io.informant.weaving.Misc", "io.informant.weaving.Misc2" })
    public static class HasStringMultipleMixin implements HasString {
        private String string;
        @MixinInit
        private void initHasString() {
            string = "a string";
        }
        public String getString() {
            return string;
        }
        public void setString(String string) {
            this.string = string;
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            captureNested = false)
    public static class NotNestingAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            captureNested = false)
    public static class NotNestingWithNoIsEnabledAdvice {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute*",
            methodArgs = { ".." }, metricName = "abc xyz")
    public static class InnerMethodAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute*",
            methodArgs = { ".." }, captureNested = false)
    public static class MultipleMethodsAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.StaticMisc", methodName = "executeStatic")
    public static class StaticAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            methodModifiers = MethodModifier.STATIC)
    public static class NonMatchingStaticAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Mis*", methodName = "execute1")
    public static class TypeNamePatternAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            methodReturn = "void")
    public static class MethodReturnVoidAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.String")
    public static class MethodReturnStringAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1",
            methodReturn = "java.lang.String")
    public static class NonMatchingMethodReturnAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithReturn",
            methodReturn = "java.lang.Number")
    public static class NonMatchingMethodReturnAdvice2 extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.StaticMisc", methodName = "executeStatic")
    public static class StaticBindTargetClassAdvice {
        public static ThreadLocal<Class<?>> onBeforeCount = new ThreadLocal<Class<?>>();
        @OnBefore
        public static void onBefore(@BindTarget Class<?> type) {
            onBeforeCount.set(type);
        }
        public static void resetThreadLocals() {
            onBeforeCount.remove();
        }
    }

    @Pointcut(typeName = "io.informant.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = { "int", "double", "long", "byte[]" })
    public static class PrimitiveAdvice extends BasicAdvice {}

    @Pointcut(typeName = "io.informant.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = { "int", "double", "*", ".." })
    public static class PrimitiveWithWildcardAdvice {
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindMethodArg int x) {
            enabledCount.increment();
            return true;
        }
        @OnBefore
        public static void onBefore(@SuppressWarnings("unused") int x) {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.PrimitiveMisc", methodName = "executePrimitive",
            methodArgs = { "int", "double", "*", ".." })
    public static class PrimitiveWithAutoboxAdvice {
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindMethodArg Object x) {
            enabledCount.increment();
            return true;
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class VeryBadAdvice {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class MoreVeryBadAdvice {
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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
    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "executeWithReturn")
    public static class MoreVeryBadAdvice2 {
        public static IntegerThreadLocal onReturnCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onThrowCount = new IntegerThreadLocal();
        public static IntegerThreadLocal onAfterCount = new IntegerThreadLocal();
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

    @Pointcut(typeName = "io.informant.weaving.Misc3", methodName = "identity",
            methodArgs = { "io.informant.weaving.BasicMisc" })
    public static class CircularClassDependencyAdvice {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

    @Pointcut(typeName = "io.informant.weaving.Misc", methodName = "execute1")
    public static class InterfaceAppearsTwiceInHierarchyAdvice {
        public static IntegerThreadLocal onBeforeCount = new IntegerThreadLocal();
        @OnBefore
        public static void onBefore() {
            onBeforeCount.increment();
        }
        public static void resetThreadLocals() {
            onBeforeCount.set(0);
        }
    }

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
