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
package org.informantproject.core.weaving;

import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectMethodArg;
import org.informantproject.api.weaving.InjectMethodName;
import org.informantproject.api.weaving.InjectReturn;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectThrowable;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.Mixin;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.OnThrow;
import org.informantproject.api.weaving.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class SomeAspect {

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "/execute[12]/")
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

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "execute1")
    public static class InjectTargetAdvice {
        public static ThreadLocal<Misc> isEnabledTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onBeforeTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onReturnTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onThrowTarget = new ThreadLocal<Misc>();
        public static ThreadLocal<Misc> onAfterTarget = new ThreadLocal<Misc>();
        @IsEnabled
        public static boolean isEnabled(@InjectTarget Misc target) {
            isEnabledTarget.set(target);
            return true;
        }
        @OnBefore
        public static void onBefore(@InjectTarget Misc target) {
            onBeforeTarget.set(target);
        }
        @OnReturn
        public static void onReturn(@InjectTarget Misc target) {
            onReturnTarget.set(target);
        }
        @OnThrow
        public static void onThrow(@InjectTarget Misc target) {
            onThrowTarget.set(target);
        }
        @OnAfter
        public static void onAfter(@InjectTarget Misc target) {
            onAfterTarget.set(target);
        }
        public static void resetThreadLocals() {
            isEnabledTarget.set(null);
            onBeforeTarget.set(null);
            onReturnTarget.set(null);
            onThrowTarget.set(null);
            onAfterTarget.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class InjectMethodArgAdvice {
        public static ThreadLocal<Object[]> isEnabledParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onBeforeParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onReturnParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onThrowParams = new ThreadLocal<Object[]>();
        public static ThreadLocal<Object[]> onAfterParams = new ThreadLocal<Object[]>();
        @IsEnabled
        public static boolean isEnabled(@InjectMethodArg String one, @InjectMethodArg int two) {
            isEnabledParams.set(new Object[] { one, two });
            return true;
        }
        @OnBefore
        public static void onBefore(@InjectMethodArg String one, @InjectMethodArg int two) {
            onBeforeParams.set(new Object[] { one, two });
        }
        @OnReturn
        public static void onReturn(@InjectMethodArg String one, @InjectMethodArg int two) {
            onReturnParams.set(new Object[] { one, two });
        }
        @OnThrow
        public static void onThrow(@InjectMethodArg String one, @InjectMethodArg int two) {
            onThrowParams.set(new Object[] { one, two });
        }
        @OnAfter
        public static void onAfter(@InjectMethodArg String one, @InjectMethodArg int two) {
            onAfterParams.set(new Object[] { one, two });
        }
        public static void resetThreadLocals() {
            isEnabledParams.set(null);
            onBeforeParams.set(null);
            onReturnParams.set(null);
            onThrowParams.set(null);
            onAfterParams.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "execute1")
    public static class InjectTravelerAdvice {
        public static ThreadLocal<String> onReturnTraveler = new ThreadLocal<String>();
        public static ThreadLocal<String> onThrowTraveler = new ThreadLocal<String>();
        public static ThreadLocal<String> onAfterTraveler = new ThreadLocal<String>();
        @OnBefore
        public static String onBefore() {
            return "a traveler";
        }
        @OnReturn
        public static void onReturn(@InjectTraveler String traveler) {
            onReturnTraveler.set(traveler);
        }
        @OnThrow
        public static void onThrow(@InjectTraveler String traveler) {
            onThrowTraveler.set(traveler);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler String traveler) {
            onAfterTraveler.set(traveler);
        }
        public static void resetThreadLocals() {
            onReturnTraveler.set(null);
            onThrowTraveler.set(null);
            onAfterTraveler.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithReturn")
    public static class InjectReturnAdvice {
        public static ThreadLocal<String> returnValue = new ThreadLocal<String>();
        @OnReturn
        public static void onReturn(@InjectReturn String value) {
            returnValue.set(value);
        }
        public static void resetThreadLocals() {
            returnValue.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "execute1")
    public static class InjectThrowableAdvice {
        public static ThreadLocal<Throwable> throwable = new ThreadLocal<Throwable>();
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t) {
            throwable.set(t);
        }
        public static void resetThreadLocals() {
            throwable.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "execute1")
    public static class InjectMethodNameAdvice {
        public static ThreadLocal<String> isEnabledMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onBeforeMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onReturnMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onThrowMethodName = new ThreadLocal<String>();
        public static ThreadLocal<String> onAfterMethodName = new ThreadLocal<String>();
        @IsEnabled
        public static boolean isEnabled(@InjectMethodName String methodName) {
            isEnabledMethodName.set(methodName);
            return true;
        }
        @OnBefore
        public static void onBefore(@InjectMethodName String methodName) {
            onBeforeMethodName.set(methodName);
        }
        @OnReturn
        public static void onReturn(@InjectMethodName String methodName) {
            onReturnMethodName.set(methodName);
        }
        @OnThrow
        public static void onThrow(@InjectMethodName String methodName) {
            onThrowMethodName.set(methodName);
        }
        @OnAfter
        public static void onAfter(@InjectMethodName String methodName) {
            onAfterMethodName.set(methodName);
        }
        public static void resetThreadLocals() {
            isEnabledMethodName.set(null);
            onBeforeMethodName.set(null);
            onReturnMethodName.set(null);
            onThrowMethodName.set(null);
            onAfterMethodName.set(null);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName =
            "executeWithReturn")
    public static class ChangeReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnReturn
        public static String onReturn(@InjectReturn String value) {
            return "modified " + value;
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithArgs",
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

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithArgs",
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

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithArgs",
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

    @Mixin(target = "org.informantproject.core.weaving.BasicMisc", mixin = HasString.class,
            mixinImpl = HasStringImpl.class)
    public static class ClassTargetedMixin {}

    @Mixin(target = "org.informantproject.core.weaving.Misc", mixin = HasString.class,
            mixinImpl = HasStringImpl.class)
    public static class InterfaceTargetedMixin {}

    public interface HasString {
        String getString();
        void setString(String string);
    }

    public static class HasStringImpl implements HasString {
        private String string = "a string";
        public String getString() {
            return string;
        }
        public void setString(String string) {
            this.string = string;
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "execute1",
            captureNested = false)
    public static class NotNestingAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc",
            methodName = "/execute.*/", methodArgs = { ".." }, metricName = "abc xyz")
    public static class InnerMethodAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "/execute.*/",
            methodArgs = { ".." }, captureNested = false)
    public static class MultipleMethodsAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.informantproject.core.weaving.StaticMisc",
            methodName = "executeStatic")
    public static class StaticAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.informantproject.core.weaving.PrimitiveMisc",
            methodName = "executePrimitive", methodArgs = { "int", "double", "long", "byte[]" })
    public static class PrimitiveAdvice extends BasicAdvice {}

    @Pointcut(typeName = "org.informantproject.core.weaving.PrimitiveMisc",
            methodName = "executePrimitive", methodArgs = { "int", "double", "/.*/", ".." })
    public static class PrimitiveWithWildcardAdvice {
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        @SuppressWarnings("unused")
        @IsEnabled
        public static boolean isEnabled(@InjectMethodArg int x) {
            enabledCount.increment();
            return true;
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.PrimitiveMisc",
            methodName = "executePrimitive", methodArgs = { "int", "double", "/.*/", ".." })
    public static class PrimitiveWithAutoboxAdvice {
        public static IntegerThreadLocal enabledCount = new IntegerThreadLocal();
        @SuppressWarnings("unused")
        @IsEnabled
        public static boolean isEnabled(@InjectMethodArg Object x) {
            enabledCount.increment();
            return true;
        }
        public static void resetThreadLocals() {
            enabledCount.set(0);
        }
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc", methodName = "executeWithArgs",
            methodArgs = { "java.lang.String", "int" })
    public static class BrokenAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnBefore
        public static Object onBefore() {
            return null;
        }
        @OnAfter
        public static void onAfter(@SuppressWarnings("unused") @InjectTraveler Object traveler) {}
    }

    @Pointcut(typeName = "org.informantproject.core.weaving.Misc3", methodName = "identity",
            methodArgs = { "org.informantproject.core.weaving.BasicMisc" })
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

    public static class IntegerThreadLocal extends ThreadLocal<Integer> {
        @Override
        public Integer initialValue() {
            return 0;
        }
        public void increment() {
            set(get() + 1);
        }
    }
}
