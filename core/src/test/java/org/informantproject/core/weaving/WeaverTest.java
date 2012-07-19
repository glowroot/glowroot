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

import static org.fest.assertions.api.Assertions.assertThat;

import java.util.List;

import org.informantproject.api.weaving.Mixin;
import org.informantproject.api.weaving.Pointcut;
import org.informantproject.core.weaving.SomeAspect.BasicAdvice;
import org.informantproject.core.weaving.SomeAspect.BrokenAdvice;
import org.informantproject.core.weaving.SomeAspect.ChangeReturnAdvice;
import org.informantproject.core.weaving.SomeAspect.CircularClassDependencyAdvice;
import org.informantproject.core.weaving.SomeAspect.ClassTargetedMixin;
import org.informantproject.core.weaving.SomeAspect.HasString;
import org.informantproject.core.weaving.SomeAspect.InjectMethodArgAdvice;
import org.informantproject.core.weaving.SomeAspect.InjectMethodNameAdvice;
import org.informantproject.core.weaving.SomeAspect.InjectReturnAdvice;
import org.informantproject.core.weaving.SomeAspect.InjectTargetAdvice;
import org.informantproject.core.weaving.SomeAspect.InjectThrowableAdvice;
import org.informantproject.core.weaving.SomeAspect.InjectTravelerAdvice;
import org.informantproject.core.weaving.SomeAspect.InnerMethodAdvice;
import org.informantproject.core.weaving.SomeAspect.InterfaceTargetedMixin;
import org.informantproject.core.weaving.SomeAspect.MethodArgsDotDotAdvice1;
import org.informantproject.core.weaving.SomeAspect.MethodArgsDotDotAdvice2;
import org.informantproject.core.weaving.SomeAspect.MethodArgsDotDotAdvice3;
import org.informantproject.core.weaving.SomeAspect.MultipleMethodsAdvice;
import org.informantproject.core.weaving.SomeAspect.NotNestingAdvice;
import org.informantproject.core.weaving.SomeAspect.PrimitiveAdvice;
import org.informantproject.core.weaving.SomeAspect.PrimitiveWithAutoboxAdvice;
import org.informantproject.core.weaving.SomeAspect.PrimitiveWithWildcardAdvice;
import org.informantproject.core.weaving.SomeAspect.StaticAdvice;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeaverTest {

    // ===================== @IsEnabled =====================

    @Test
    public void shouldExecuteEnabledAdvice() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        BasicAdvice.enable();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(1);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldExecuteEnabledAdviceOnThrow() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        BasicAdvice.enable();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BasicAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(1);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotExecuteDisabledAdvice() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        BasicAdvice.disable();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldNotExecuteDisabledAdviceOnThrow() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        BasicAdvice.disable();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BasicAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(0);
    }

    // ===================== @InjectTarget =====================

    @Test
    public void shouldInjectTarget() throws Exception {
        // given
        InjectTargetAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InjectTargetAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(InjectTargetAdvice.isEnabledTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onBeforeTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onReturnTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onThrowTarget.get()).isNull();
        assertThat(InjectTargetAdvice.onAfterTarget.get()).isEqualTo(test);
    }

    @Test
    public void shouldInjectTargetOnThrow() throws Exception {
        // given
        InjectTargetAdvice.resetThreadLocals();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, InjectTargetAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(InjectTargetAdvice.isEnabledTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onBeforeTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onReturnTarget.get()).isNull();
        assertThat(InjectTargetAdvice.onThrowTarget.get()).isEqualTo(test);
        assertThat(InjectTargetAdvice.onAfterTarget.get()).isEqualTo(test);
    }

    // ===================== @InjectMethodArg =====================

    @Test
    public void shouldInjectMethodArgs() throws Exception {
        // given
        InjectMethodArgAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InjectMethodArgAdvice.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        Object[] parameters = new Object[] { "one", 2 };
        assertThat(InjectMethodArgAdvice.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onReturnParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onThrowParams.get()).isNull();
        assertThat(InjectMethodArgAdvice.onAfterParams.get()).isEqualTo(parameters);
    }

    @Test
    public void shouldInjectMethodArgOnThrow() throws Exception {
        // given
        InjectMethodArgAdvice.resetThreadLocals();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, InjectMethodArgAdvice.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (Throwable t) {
        }
        // then
        Object[] parameters = new Object[] { "one", 2 };
        assertThat(InjectMethodArgAdvice.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onReturnParams.get()).isNull();
        assertThat(InjectMethodArgAdvice.onThrowParams.get()).isEqualTo(parameters);
        assertThat(InjectMethodArgAdvice.onAfterParams.get()).isEqualTo(parameters);
    }

    // ===================== @InjectTraveler =====================

    @Test
    public void shouldInjectTraveler() throws Exception {
        // given
        InjectTravelerAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InjectTravelerAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(InjectTravelerAdvice.onReturnTraveler.get()).isEqualTo("a traveler");
        assertThat(InjectTravelerAdvice.onThrowTraveler.get()).isNull();
        assertThat(InjectTravelerAdvice.onAfterTraveler.get()).isEqualTo("a traveler");
    }

    @Test
    public void shouldInjectTravelerOnThrow() throws Exception {
        // given
        InjectTravelerAdvice.resetThreadLocals();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, InjectTravelerAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(InjectTravelerAdvice.onReturnTraveler.get()).isNull();
        assertThat(InjectTravelerAdvice.onThrowTraveler.get()).isEqualTo("a traveler");
        assertThat(InjectTravelerAdvice.onAfterTraveler.get()).isEqualTo("a traveler");
    }

    // ===================== @InjectReturn =====================

    @Test
    public void shouldInjectReturn() throws Exception {
        // given
        InjectReturnAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InjectReturnAdvice.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(InjectReturnAdvice.returnValue.get()).isEqualTo("xyz");
    }

    // ===================== @InjectThrowable =====================

    @Test
    public void shouldInjectThrowable() throws Exception {
        // given
        InjectThrowableAdvice.resetThreadLocals();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, InjectThrowableAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(InjectThrowableAdvice.throwable.get()).isNotNull();
    }

    // ===================== @InjectMethodName =====================

    @Test
    public void shouldInjectMethodName() throws Exception {
        // given
        InjectMethodNameAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InjectMethodNameAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(InjectMethodNameAdvice.isEnabledMethodName.get()).isEqualTo("execute1");
        assertThat(InjectMethodNameAdvice.onBeforeMethodName.get()).isEqualTo("execute1");
        assertThat(InjectMethodNameAdvice.onReturnMethodName.get()).isEqualTo("execute1");
        assertThat(InjectMethodNameAdvice.onThrowMethodName.get()).isNull();
        assertThat(InjectMethodNameAdvice.onAfterMethodName.get()).isEqualTo("execute1");
    }

    // ===================== change return value =====================

    @Test
    public void shouldChangeReturnValue() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, ChangeReturnAdvice.class);
        // when
        String returnValue = test.executeWithReturn();
        // then
        assertThat(returnValue).isEqualTo("modified xyz");
    }

    // ===================== inheritance =====================

    @Test
    public void shouldNotWeaveIfDoesNotOverrideMatch() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        Misc2 test = newWovenObject(BasicMisc.class, Misc2.class, BasicAdvice.class);
        // when
        test.execute2();
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(0);
    }

    // ===================== methodArgs '..' =====================

    @Test
    public void shouldMatchMethodArgsDotDot1() throws Exception {
        // given
        MethodArgsDotDotAdvice1.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MethodArgsDotDotAdvice1.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(MethodArgsDotDotAdvice1.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldMatchMethodArgsDotDot2() throws Exception {
        // given
        MethodArgsDotDotAdvice2.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MethodArgsDotDotAdvice2.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(MethodArgsDotDotAdvice2.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldMatchMethodArgsDotDot3() throws Exception {
        // given
        MethodArgsDotDotAdvice3.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MethodArgsDotDotAdvice3.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(MethodArgsDotDotAdvice3.onBeforeCount.get()).isEqualTo(1);
    }

    // ===================== @Mixin =====================

    @Test
    public void shouldMixinToClass() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, ClassTargetedMixin.class,
                HasString.class);
        // when
        ((HasString) test).setString("another value");
        // then
        assertThat(((HasString) test).getString()).isEqualTo("another value");
    }

    @Test
    public void shouldMixinToInterface() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, InterfaceTargetedMixin.class,
                HasString.class);
        // when
        ((HasString) test).setString("another value");
        // then
        assertThat(((HasString) test).getString()).isEqualTo("another value");
    }

    // ===================== @Pointcut.nestable =====================

    @Test
    public void shouldNotNestPointcuts() throws Exception {
        // given
        NotNestingAdvice.resetThreadLocals();
        Misc test = newWovenObject(NestingMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(NotNestingAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(NotNestingAdvice.onReturnCount.get()).isEqualTo(1);
        assertThat(NotNestingAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(NotNestingAdvice.onAfterCount.get()).isEqualTo(1);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNotNestPointcuts2() throws Exception {
        // given
        NotNestingAdvice.resetThreadLocals();
        Misc test = newWovenObject(NestingMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        test.execute1();
        // then
        assertThat(NotNestingAdvice.onBeforeCount.get()).isEqualTo(2);
        assertThat(NotNestingAdvice.onReturnCount.get()).isEqualTo(2);
        assertThat(NotNestingAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(NotNestingAdvice.onAfterCount.get()).isEqualTo(2);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNotNestPointcuts3() throws Exception {
        // given
        NotNestingAdvice.resetThreadLocals();
        Misc test = newWovenObject(NestingAnotherMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(NotNestingAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(NotNestingAdvice.onReturnCount.get()).isEqualTo(1);
        assertThat(NotNestingAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(NotNestingAdvice.onAfterCount.get()).isEqualTo(1);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNestPointcuts() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        Misc test = newWovenObject(NestingMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(2);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(2);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(2);
    }

    // ===================== @Pointcut.innerMethod =====================

    @Test
    public void shouldWrapInMarkerMethod() throws Exception {
        // given
        Misc test = newWovenObject(InnerMethodMisc.class, Misc.class, InnerMethodAdvice.class);
        // when
        String methodName = test.executeWithReturn();
        // then
        assertThat(methodName).isNotNull();
        assertThat(methodName.substring(0, methodName.lastIndexOf("$")))
                .isEqualTo("executeWithReturn$informant$metric$abc$xyz");
    }

    // ===================== static pointcuts =====================

    @Test
    public void shouldWeaveStaticMethod() throws Exception {
        // given
        StaticAdvice.resetThreadLocals();
        StaticAdvice.enable();
        Misc test = newWovenObject(StaticMisc.class, Misc.class, StaticAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(StaticAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(StaticAdvice.onReturnCount.get()).isEqualTo(1);
        assertThat(StaticAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(StaticAdvice.onAfterCount.get()).isEqualTo(1);
    }

    // ===================== primitive args =====================

    @Test
    public void shouldWeaveMethodWithPrimitiveArgs() throws Exception {
        // given
        PrimitiveAdvice.resetThreadLocals();
        PrimitiveAdvice.enable();
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class, PrimitiveAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(PrimitiveAdvice.onBeforeCount.get()).isEqualTo(1);
        assertThat(PrimitiveAdvice.onReturnCount.get()).isEqualTo(1);
        assertThat(PrimitiveAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(PrimitiveAdvice.onAfterCount.get()).isEqualTo(1);
    }

    // ===================== wildcard args =====================

    @Test
    public void shouldWeaveMethodWithWildcardArgs() throws Exception {
        // given
        PrimitiveWithWildcardAdvice.resetThreadLocals();
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                PrimitiveWithWildcardAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(PrimitiveWithWildcardAdvice.enabledCount.get()).isEqualTo(1);
    }

    // ===================== autobox args =====================

    @Test
    public void shouldWeaveMethodWithAutoboxArgs() throws Exception {
        // given
        PrimitiveWithAutoboxAdvice.resetThreadLocals();
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                PrimitiveWithAutoboxAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(PrimitiveWithAutoboxAdvice.enabledCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandlePointcutWithMultipleMethods() throws Exception {
        // given
        BasicAdvice.resetThreadLocals();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MultipleMethodsAdvice.class);
        // when
        test.execute1();
        test.executeWithArgs("one", 2);
        // then
        assertThat(BasicAdvice.onBeforeCount.get()).isEqualTo(2);
        assertThat(BasicAdvice.onReturnCount.get()).isEqualTo(2);
        assertThat(BasicAdvice.onThrowCount.get()).isEqualTo(0);
        assertThat(BasicAdvice.onAfterCount.get()).isEqualTo(2);
    }

    @Test
    public void shouldNotDisruptInnerTryCatch() throws Exception {
        // given
        Misc test = newWovenObject(InnerTryCatchMisc.class, Misc.class, BasicAdvice.class,
                HasString.class);
        // when
        test.execute1();
        // then
        assertThat(test.executeWithReturn()).isEqualTo("caught");
    }

    @Test
    public void shouldNotBomb() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BrokenAdvice.class);
        // when
        test.executeWithArgs("one", 2);
        // then should not bomb
    }

    @Test
    public void shouldNotBomb2() throws Exception {
        // given
        Misc test = newWovenObject(AccessibilityMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then should not bomb
    }

    @Test
    // weaving an interface method that references a concrete class that implements that interface
    // is supported
    public void shouldHandleCircularDependency() throws Exception {
        // given
        CircularClassDependencyAdvice.resetThreadLocals();
        // when
        newWovenObject(BasicMisc.class, Misc.class, CircularClassDependencyAdvice.class);
        // then should not bomb
    }

    public static <S, T extends S> S newWovenObject(Class<T> implClass, Class<S> bridgeClass,
            Class<?> adviceClass, Class<?>... extraBridgeClasses) throws Exception {

        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        List<Advice> advisors;
        if (pointcut == null) {
            advisors = ImmutableList.of();
        } else {
            Advice advice = new Advice(pointcut, adviceClass);
            advisors = ImmutableList.of(advice);
        }
        Mixin mixin = adviceClass.getAnnotation(Mixin.class);
        List<Mixin> mixins;
        if (mixin == null) {
            mixins = ImmutableList.of();
        } else {
            mixins = ImmutableList.of(mixin);
        }
        // adviceClass is passed as bridgeable so that the static threadlocals will be accessible
        // for test verification
        List<Class<?>> bridgeClasses = Lists.asList(bridgeClass, adviceClass, extraBridgeClasses);
        IsolatedWeavingClassLoader weavingClassLoader = new IsolatedWeavingClassLoader(mixins,
                advisors, Iterables.toArray(bridgeClasses, Class.class));
        return weavingClassLoader.newInstance(implClass, bridgeClass);
    }
}
