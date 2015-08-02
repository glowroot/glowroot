/*
 * Copyright 2012-2015 the original author or authors.
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

import java.lang.reflect.Array;

import com.google.common.base.StandardSystemProperty;
import com.google.common.collect.ImmutableList;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.plugin.api.OptionalReturn;
import org.glowroot.plugin.api.weaving.Mixin;
import org.glowroot.plugin.api.weaving.Pointcut;
import org.glowroot.plugin.api.weaving.Shim;
import org.glowroot.weaving.AbstractMisc.ExtendsAbstractMisc;
import org.glowroot.weaving.AbstractNotMisc.ExtendsAbstractNotMisc;
import org.glowroot.weaving.SomeAspect.BasicAdvice;
import org.glowroot.weaving.SomeAspect.BasicLowPriorityAdvice;
import org.glowroot.weaving.SomeAspect.BasicMiscAllConstructorAdvice;
import org.glowroot.weaving.SomeAspect.BasicMiscConstructorAdvice;
import org.glowroot.weaving.SomeAspect.BasicWithInnerClassAdvice;
import org.glowroot.weaving.SomeAspect.BasicWithInnerClassArgAdvice;
import org.glowroot.weaving.SomeAspect.BindAutoboxedReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindClassMetaAdvice;
import org.glowroot.weaving.SomeAspect.BindMethodMetaAdvice;
import org.glowroot.weaving.SomeAspect.BindMethodMetaArrayAdvice;
import org.glowroot.weaving.SomeAspect.BindMethodMetaReturnArrayAdvice;
import org.glowroot.weaving.SomeAspect.BindMethodNameAdvice;
import org.glowroot.weaving.SomeAspect.BindOptionalPrimitiveReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindOptionalReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindOptionalVoidReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindParameterAdvice;
import org.glowroot.weaving.SomeAspect.BindParameterArrayAdvice;
import org.glowroot.weaving.SomeAspect.BindPrimitiveBooleanTravelerAdvice;
import org.glowroot.weaving.SomeAspect.BindPrimitiveReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindPrimitiveTravelerAdvice;
import org.glowroot.weaving.SomeAspect.BindReceiverAdvice;
import org.glowroot.weaving.SomeAspect.BindReturnAdvice;
import org.glowroot.weaving.SomeAspect.BindThrowableAdvice;
import org.glowroot.weaving.SomeAspect.BindTravelerAdvice;
import org.glowroot.weaving.SomeAspect.BrokenAdvice;
import org.glowroot.weaving.SomeAspect.ChangeReturnAdvice;
import org.glowroot.weaving.SomeAspect.CircularClassDependencyAdvice;
import org.glowroot.weaving.SomeAspect.ClassNamePatternAdvice;
import org.glowroot.weaving.SomeAspect.FinalMethodAdvice;
import org.glowroot.weaving.SomeAspect.HasString;
import org.glowroot.weaving.SomeAspect.HasStringClassMixin;
import org.glowroot.weaving.SomeAspect.HasStringInterfaceMixin;
import org.glowroot.weaving.SomeAspect.HasStringMultipleMixin;
import org.glowroot.weaving.SomeAspect.InnerMethodAdvice;
import org.glowroot.weaving.SomeAspect.InterfaceAppearsTwiceInHierarchyAdvice;
import org.glowroot.weaving.SomeAspect.MatchingPublicNonStaticAdvice;
import org.glowroot.weaving.SomeAspect.MethodParametersBadDotDotAdvice1;
import org.glowroot.weaving.SomeAspect.MethodParametersDotDotAdvice1;
import org.glowroot.weaving.SomeAspect.MethodParametersDotDotAdvice2;
import org.glowroot.weaving.SomeAspect.MethodParametersDotDotAdvice3;
import org.glowroot.weaving.SomeAspect.MethodReturnCharSequenceAdvice;
import org.glowroot.weaving.SomeAspect.MethodReturnStringAdvice;
import org.glowroot.weaving.SomeAspect.MethodReturnVoidAdvice;
import org.glowroot.weaving.SomeAspect.MultipleMethodsAdvice;
import org.glowroot.weaving.SomeAspect.NonMatchingMethodReturnAdvice;
import org.glowroot.weaving.SomeAspect.NonMatchingMethodReturnAdvice2;
import org.glowroot.weaving.SomeAspect.NonMatchingStaticAdvice;
import org.glowroot.weaving.SomeAspect.NotNestingAdvice;
import org.glowroot.weaving.SomeAspect.NotNestingWithNoIsEnabledAdvice;
import org.glowroot.weaving.SomeAspect.PrimitiveAdvice;
import org.glowroot.weaving.SomeAspect.PrimitiveWithAutoboxAdvice;
import org.glowroot.weaving.SomeAspect.PrimitiveWithWildcardAdvice;
import org.glowroot.weaving.SomeAspect.Shimmy;
import org.glowroot.weaving.SomeAspect.StaticAdvice;
import org.glowroot.weaving.SomeAspect.SuperBasicAdvice;
import org.glowroot.weaving.SomeAspect.TargetedAdvice;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice2;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice3;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice4;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice5;
import org.glowroot.weaving.SomeAspect.TestBytecodeWithStackFramesAdvice6;
import org.glowroot.weaving.SomeAspect.TestClassMeta;
import org.glowroot.weaving.SomeAspect.TestMethodMeta;
import org.glowroot.weaving.SomeAspect.TestTroublesomeBytecodeAdvice;
import org.glowroot.weaving.SomeAspect.ThrowInOnBeforeAdvice;
import org.glowroot.weaving.SomeAspect.ThrowableToStringAdvice;
import org.glowroot.weaving.SomeAspect.WildMethodAdvice;
import org.glowroot.weaving.SomeAspectThreadLocals.IntegerThreadLocal;
import org.glowroot.weaving.WeavingTimerService.WeavingTimer;
import org.glowroot.weaving.other.ArrayMisc;

import static org.assertj.core.api.Assertions.assertThat;

public class WeaverTest {

    @Before
    public void before() {
        SomeAspectThreadLocals.resetThreadLocals();
    }

    // ===================== @IsEnabled =====================

    @Test
    public void shouldExecuteEnabledAdvice() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldExecuteEnabledAdviceOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BasicAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldExecuteEnabledAdviceOnOnlyThrow() throws Exception {
        // given
        Misc test = newWovenObject(OnlyThrowingMisc.class, Misc.class, BasicAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotExecuteDisabledAdvice() throws Exception {
        // given
        BasicAdvice.disable();
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldNotExecuteDisabledAdviceOnThrow() throws Exception {
        // given
        BasicAdvice.disable();
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BasicAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    // ===================== @BindReceiver =====================

    @Test
    public void shouldBindReceiver() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindReceiverAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.isEnabledReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onBeforeReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onReturnReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onThrowReceiver.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterReceiver.get()).isEqualTo(test);
    }

    @Test
    public void shouldBindReceiverOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindReceiverAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.isEnabledReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onBeforeReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onReturnReceiver.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowReceiver.get()).isEqualTo(test);
        assertThat(SomeAspectThreadLocals.onAfterReceiver.get()).isEqualTo(test);
    }

    // ===================== @BindParameter =====================

    @Test
    public void shouldBindParameters() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindParameterAdvice.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        Object[] parameters = new Object[] {"one", 2};
        assertThat(SomeAspectThreadLocals.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onReturnParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onThrowParams.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterParams.get()).isEqualTo(parameters);
    }

    @Test
    public void shouldBindParameterOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindParameterAdvice.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (Throwable t) {
        }
        // then
        Object[] parameters = new Object[] {"one", 2};
        assertThat(SomeAspectThreadLocals.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onReturnParams.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onAfterParams.get()).isEqualTo(parameters);
    }

    // ===================== @BindParameterArray =====================

    @Test
    public void shouldBindParameterArray() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindParameterArrayAdvice.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        Object[] parameters = new Object[] {"one", 2};
        assertThat(SomeAspectThreadLocals.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onReturnParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onThrowParams.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterParams.get()).isEqualTo(parameters);
    }

    @Test
    public void shouldBindParameterArrayOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindParameterArrayAdvice.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (Throwable t) {
        }
        // then
        Object[] parameters = new Object[] {"one", 2};
        assertThat(SomeAspectThreadLocals.isEnabledParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onBeforeParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onReturnParams.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowParams.get()).isEqualTo(parameters);
        assertThat(SomeAspectThreadLocals.onAfterParams.get()).isEqualTo(parameters);
    }

    // ===================== @BindTraveler =====================

    @Test
    public void shouldBindTraveler() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindTravelerAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isEqualTo("a traveler");
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo("a traveler");
    }

    @Test
    public void shouldBindPrimitiveTraveler() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindPrimitiveTravelerAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isEqualTo(3);
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo(3);
    }

    @Test
    public void shouldBindPrimitiveBooleanTraveler() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                BindPrimitiveBooleanTravelerAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isEqualTo(true);
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo(true);
    }

    @Test
    public void shouldBindTravelerOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindTravelerAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.onReturnTraveler.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowTraveler.get()).isEqualTo("a traveler");
        assertThat(SomeAspectThreadLocals.onAfterTraveler.get()).isEqualTo("a traveler");
    }

    // ===================== @BindClassMeta =====================

    @Test
    public void shouldBindClassMeta() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindClassMetaAdvice.class,
                TestClassMeta.class);
        // when
        test.execute1();
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        assertThat(SomeAspectThreadLocals.isEnabledClassMeta.get().getClazzName())
                .isEqualTo(BasicMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onBeforeClassMeta.get().getClazzName())
                .isEqualTo(BasicMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onReturnClassMeta.get().getClazzName())
                .isEqualTo(BasicMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onThrowClassMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterClassMeta.get().getClazzName())
                .isEqualTo(BasicMisc.class.getName());
    }

    @Test
    public void shouldBindClassMetaOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindClassMetaAdvice.class,
                TestClassMeta.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        assertThat(SomeAspectThreadLocals.isEnabledClassMeta.get().getClazzName())
                .isEqualTo(ThrowingMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onBeforeClassMeta.get().getClazzName())
                .isEqualTo(ThrowingMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onReturnClassMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowClassMeta.get().getClazzName())
                .isEqualTo(ThrowingMisc.class.getName());
        assertThat(SomeAspectThreadLocals.onAfterClassMeta.get().getClazzName())
                .isEqualTo(ThrowingMisc.class.getName());
    }

    // ===================== @BindMethodMeta =====================

    @Test
    public void shouldBindMethodMeta() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindMethodMetaAdvice.class,
                TestMethodMeta.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getDeclaringClassName())
                .isEqualTo(BasicMisc.class.getName());
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getReturnTypeName())
                .isEqualTo(void.class.getName());
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getParameterTypeNames())
                .containsExactly(String.class.getName(), int.class.getName());
        assertThat(SomeAspectThreadLocals.onBeforeMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
        assertThat(SomeAspectThreadLocals.onReturnMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
        assertThat(SomeAspectThreadLocals.onThrowMethodMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
    }

    @Test
    public void shouldBindMethodMetaOnThrow() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindMethodMetaAdvice.class,
                TestMethodMeta.class);
        // when
        try {
            test.executeWithArgs("one", 2);
        } catch (Throwable t) {
        }
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getDeclaringClassName())
                .isEqualTo(ThrowingMisc.class.getName());
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getReturnTypeName())
                .isEqualTo(void.class.getName());
        assertThat(SomeAspectThreadLocals.isEnabledMethodMeta.get().getParameterTypeNames())
                .containsExactly(String.class.getName(), int.class.getName());
        assertThat(SomeAspectThreadLocals.onBeforeMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
        assertThat(SomeAspectThreadLocals.onReturnMethodMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onThrowMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
        assertThat(SomeAspectThreadLocals.onAfterMethodMeta.get())
                .isEqualTo(SomeAspectThreadLocals.isEnabledMethodMeta.get());
    }

    @Test
    public void shouldBindMethodMetaArrays() throws Exception {
        // given
        Misc test = newWovenObject(ArrayMisc.class, Misc.class, BindMethodMetaArrayAdvice.class,
                TestMethodMeta.class);
        // when
        test.execute1();
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        TestMethodMeta testMethodMeta = SomeAspectThreadLocals.isEnabledMethodMeta.get();
        assertThat(testMethodMeta.getDeclaringClassName()).isEqualTo(ArrayMisc.class.getName());
        assertThat(testMethodMeta.getReturnTypeName()).isEqualTo(void.class.getName());
        Class<?> somethingPrivateClass =
                Class.forName("org.glowroot.weaving.other.ArrayMisc$SomethingPrivate");
        Class<?> somethingPrivateArrayClass =
                Array.newInstance(somethingPrivateClass, 0).getClass();
        assertThat(testMethodMeta.getParameterTypeNames()).containsExactly(byte[].class.getName(),
                Object[][][].class.getName(), somethingPrivateArrayClass.getName());
        assertThat(SomeAspectThreadLocals.onBeforeMethodMeta.get()).isEqualTo(testMethodMeta);
        assertThat(SomeAspectThreadLocals.onReturnMethodMeta.get()).isEqualTo(testMethodMeta);
        assertThat(SomeAspectThreadLocals.onThrowMethodMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterMethodMeta.get()).isEqualTo(testMethodMeta);
    }

    @Test
    public void shouldBindMethodMetaReturnArray() throws Exception {
        // given
        Misc test = newWovenObject(ArrayMisc.class, Misc.class,
                BindMethodMetaReturnArrayAdvice.class, TestMethodMeta.class);
        // when
        test.execute1();
        // then
        // can't compare Class objects directly since they are in different class loaders due to
        // IsolatedWeavingClassLoader
        TestMethodMeta testMethodMeta = SomeAspectThreadLocals.isEnabledMethodMeta.get();
        assertThat(testMethodMeta.getDeclaringClassName()).isEqualTo(ArrayMisc.class.getName());
        assertThat(testMethodMeta.getReturnTypeName()).isEqualTo(int[].class.getName());
        assertThat(testMethodMeta.getParameterTypeNames()).isEmpty();
        assertThat(SomeAspectThreadLocals.onBeforeMethodMeta.get()).isEqualTo(testMethodMeta);
        assertThat(SomeAspectThreadLocals.onReturnMethodMeta.get()).isEqualTo(testMethodMeta);
        assertThat(SomeAspectThreadLocals.onThrowMethodMeta.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterMethodMeta.get()).isEqualTo(testMethodMeta);
    }

    // ===================== @BindReturn =====================

    @Test
    public void shouldBindReturn() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindReturnAdvice.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(SomeAspectThreadLocals.returnValue.get()).isEqualTo("xyz");
    }

    @Test
    public void shouldBindPrimitiveReturn() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                BindPrimitiveReturnAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.returnValue.get()).isEqualTo(4);
    }

    @Test
    public void shouldBindAutoboxedReturn() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                BindAutoboxedReturnAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.returnValue.get()).isEqualTo(4);
    }

    // ===================== @BindOptionalReturn =====================

    @Test
    public void shouldBindOptionalReturn() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindOptionalReturnAdvice.class,
                OptionalReturn.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(SomeAspectThreadLocals.optionalReturnValue.get().isVoid()).isFalse();
        assertThat(SomeAspectThreadLocals.optionalReturnValue.get().getValue()).isEqualTo("xyz");
    }

    @Test
    public void shouldBindOptionalVoidReturn() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindOptionalVoidReturnAdvice.class,
                OptionalReturn.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.optionalReturnValue.get().isVoid()).isTrue();
    }

    @Test
    public void shouldBindOptionalPrimitiveReturn() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                BindOptionalPrimitiveReturnAdvice.class, OptionalReturn.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.optionalReturnValue.get().isVoid()).isFalse();
        assertThat(SomeAspectThreadLocals.optionalReturnValue.get().getValue())
                .isEqualTo(Integer.valueOf(4));
    }

    // ===================== @BindThrowable =====================

    @Test
    public void shouldBindThrowable() throws Exception {
        // given
        Misc test = newWovenObject(ThrowingMisc.class, Misc.class, BindThrowableAdvice.class);
        // when
        try {
            test.execute1();
        } catch (Throwable t) {
        }
        // then
        assertThat(SomeAspectThreadLocals.throwable.get()).isNotNull();
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(1);
    }

    // ===================== @BindMethodName =====================

    @Test
    public void shouldBindMethodName() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BindMethodNameAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.isEnabledMethodName.get()).isEqualTo("execute1");
        assertThat(SomeAspectThreadLocals.onBeforeMethodName.get()).isEqualTo("execute1");
        assertThat(SomeAspectThreadLocals.onReturnMethodName.get()).isEqualTo("execute1");
        assertThat(SomeAspectThreadLocals.onThrowMethodName.get()).isNull();
        assertThat(SomeAspectThreadLocals.onAfterMethodName.get()).isEqualTo("execute1");
    }

    // ===================== change return value =====================

    @Test
    public void shouldChangeReturnValue() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, ChangeReturnAdvice.class);
        // when
        CharSequence returnValue = test.executeWithReturn();
        // then
        assertThat(returnValue).isEqualTo("modified xyz:executeWithReturn");
    }

    // ===================== inheritance =====================

    @Test
    public void shouldNotWeaveIfDoesNotOverrideMatch() throws Exception {
        // given
        Misc2 test = newWovenObject(BasicMisc.class, Misc2.class, BasicAdvice.class);
        // when
        test.execute2();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    // ===================== methodParameters '..' =====================

    @Test
    public void shouldMatchMethodParametersDotDot1() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MethodParametersDotDotAdvice1.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotMatchMethodParametersBadDotDot1() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MethodParametersBadDotDotAdvice1.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldMatchMethodParametersDotDot2() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MethodParametersDotDotAdvice2.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldMatchMethodParametersDotDot3() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MethodParametersDotDotAdvice3.class);
        // when
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    // ===================== @Pointcut.classHierarchyRestriction =====================

    @Test
    public void shouldExecuteTargetedAdvice() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, TargetedAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldExecuteSubTargetedAdvice() throws Exception {
        // given
        Misc test = newWovenObject(SubBasicMisc.class, Misc.class, TargetedAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotExecuteNotTargetedAdvice() throws Exception {
        // given
        Misc test = newWovenObject(NestingMisc.class, Misc.class, TargetedAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    // ===================== throw in lower priority @OnBefore =====================

    // motivation for this test: any dangerous code in @OnBefore should occur before calling
    // pluginServices.start..., since @OnAfter will not be called if an exception occurs
    // however, if there are multiple pointcuts for one method, the dangerous code in @OnBefore
    // of the lower priority pointcut occurs after the @OnBefore of the higher priority pointcut
    // and so if the dangerous code in the lower priority pointcut throws an exception, need to make
    // sure the @OnAfter of the higher priority pointcut is still called

    @Test
    public void shouldStillCallOnAfterOfHigherPriorityPointcut() throws Exception {
        // given
        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        Advice advice1 = new AdviceBuilder(BasicAdvice.class, false).build();
        Advice advice3 = new AdviceBuilder(BindThrowableAdvice.class, false).build();
        Advice advice2 = new AdviceBuilder(ThrowInOnBeforeAdvice.class, false).build();
        Advice advice4 = new AdviceBuilder(BasicLowPriorityAdvice.class, false).build();
        loader.setAdvisors(ImmutableList.of(advice1, advice2, advice3, advice4));
        loader.setWeavingTimerService(NopWeavingTimerService.INSTANCE);
        // SomeAspectThreadLocals is passed as bridgeable so that the static thread locals will be
        // accessible for test verification
        loader.addBridgeClasses(Misc.class, SomeAspectThreadLocals.class, IntegerThreadLocal.class);
        Misc test = loader.build().newInstance(BasicMisc.class, Misc.class);
        // when
        RuntimeException exception = null;
        try {
            test.execute1();
        } catch (RuntimeException e) {
            exception = e;
        }
        // then
        assertThat(exception.getMessage()).isEqualTo("Abxy");
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.throwable.get().getMessage()).isEqualTo("Abxy");
    }

    // ===================== @Shim =====================

    @Test
    public void shouldShim() throws Exception {
        // given
        Misc test = newWovenObject(ShimmedMisc.class, Misc.class, Shimmy.class, Shimmy.class);
        // when
        ((Shimmy) test).shimmySetString("another value");
        // then
        assertThat(((Shimmy) test).shimmyGetString()).isEqualTo("another value");
    }

    // ===================== @Mixin =====================

    @Test
    public void shouldMixinToClass() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, HasStringClassMixin.class,
                HasString.class);
        // when
        ((HasString) test).setString("another value");
        // then
        assertThat(((HasString) test).getString()).isEqualTo("another value");
    }

    @Test
    public void shouldMixinToInterface() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, HasStringInterfaceMixin.class,
                HasString.class);
        // when
        ((HasString) test).setString("another value");
        // then
        assertThat(((HasString) test).getString()).isEqualTo("another value");
    }

    @Test
    public void shouldMixinOnlyOnce() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, HasStringMultipleMixin.class,
                HasString.class);
        // when
        ((HasString) test).setString("another value");
        // then
        assertThat(((HasString) test).getString()).isEqualTo("another value");
    }

    @Test
    public void shouldMixinAndCallInitExactlyOnce() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, HasStringClassMixin.class,
                HasString.class);
        // when
        // then
        assertThat(((HasString) test).getString()).isEqualTo("a string");
    }

    // ===================== @Pointcut.nestable =====================

    @Test
    public void shouldNotNestPointcuts() throws Exception {
        // given
        Misc test = newWovenObject(NestingMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNotNestPointcuts2() throws Exception {
        // given
        Misc test = newWovenObject(NestingMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(2);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNotNestPointcuts3() throws Exception {
        // given
        Misc test = newWovenObject(NestingAnotherMisc.class, Misc.class, NotNestingAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    @Test
    public void shouldNestPointcuts() throws Exception {
        // given
        Misc test = newWovenObject(NestingMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(2);
    }

    @Test
    public void shouldNotNestPointcutsEvenWithNoIsEnabled() throws Exception {
        // given
        Misc test = newWovenObject(NestingMisc.class, Misc.class,
                NotNestingWithNoIsEnabledAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
        assertThat(test.executeWithReturn()).isEqualTo("yes");
    }

    // ===================== @Pointcut.innerMethod =====================

    @Test
    public void shouldWrapInMarkerMethod() throws Exception {
        // given
        Misc test = newWovenObject(InnerMethodMisc.class, Misc.class, InnerMethodAdvice.class);
        // when
        CharSequence methodName = test.executeWithReturn();
        // then
        assertThat(methodName).isNotNull();
        assertThat(methodName.toString())
                .matches("executeWithReturn\\$glowroot\\$timer\\$abc\\$xyz\\$\\d+");
    }

    // ===================== static pointcuts =====================

    @Test
    public void shouldWeaveStaticMethod() throws Exception {
        // given
        Misc test = newWovenObject(StaticMisc.class, Misc.class, StaticAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    // ===================== primitive args =====================

    @Test
    public void shouldWeaveMethodWithPrimitiveArgs() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class, PrimitiveAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    // ===================== wildcard args =====================

    @Test
    public void shouldWeaveMethodWithWildcardArgs() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                PrimitiveWithWildcardAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotBombWithWithWildcardArg() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, WildMethodAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    // ===================== type name pattern =====================

    @Test
    public void shouldWeaveTypeWithNamePattern() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class, ClassNamePatternAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    // ===================== autobox args =====================

    @Test
    public void shouldWeaveMethodWithAutoboxArgs() throws Exception {
        // given
        Misc test = newWovenObject(PrimitiveMisc.class, Misc.class,
                PrimitiveWithAutoboxAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
    }

    // ===================== return type matching =====================

    @Test
    public void shouldMatchMethodReturningVoid() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MethodReturnVoidAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldMatchMethodReturningCharSequence() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MethodReturnCharSequenceAdvice.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldNotMatchMethodReturningString() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MethodReturnStringAdvice.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldNotMatchMethodBasedOnReturnType() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                NonMatchingMethodReturnAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldNotMatchMethodBasedOnReturnType2() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                NonMatchingMethodReturnAdvice2.class);
        // when
        test.executeWithReturn();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
    }

    // ===================== constructor =====================

    @Test
    public void shouldHandleConstructorPointcut() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicMiscConstructorAdvice.class);
        // reset thread locals after instantiated BasicMisc, to avoid counting that constructor call
        SomeAspectThreadLocals.resetThreadLocals();
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    // this is just a test to show the (undesirable) behavior of constructor advice not being nested
    //
    // see comment on above test and same comment in AdviceMatcher
    @Test
    public void shouldVerifyConstructorPointcutsAreNotNested() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                BasicMiscAllConstructorAdvice.class);
        // reset thread locals after instantiated BasicMisc, to avoid counting that constructor call
        SomeAspectThreadLocals.resetThreadLocals();
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.orderedEvents.get()).containsExactly("isEnabled",
                "onBefore", "onReturn", "onAfter", "isEnabled", "onBefore", "onReturn", "onAfter");
    }

    @Test
    public void shouldHandleInheritedMethodFulfillingAnInterface() throws Exception {
        // given
        Misc test = newWovenObject(ExtendsAbstractNotMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleInheritedMethod() throws Exception {
        // given
        SuperBasic test = newWovenObject(BasicMisc.class, SuperBasic.class, SuperBasicAdvice.class);
        // when
        test.callSuperBasic();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleInheritedPublicMethodFromPackagePrivateClass() throws Exception {
        // given
        Misc test = newWovenObject(ExtendsPackagePrivateMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleSubInheritedMethod() throws Exception {
        // given
        SuperBasic test = newWovenObject(BasicMisc.class, SuperBasic.class, SuperBasicAdvice.class);
        // when
        test.callSuperBasic();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleSubInheritedFromClassInBootstrapClassLoader() throws Exception {
        // given
        Exception test = newWovenObject(SubException.class, Exception.class,
                ThrowableToStringAdvice.class);
        // when
        test.toString();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleInnerClassArg() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, BasicWithInnerClassArgAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleInnerClass() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.InnerMisc.class, Misc.class,
                BasicWithInnerClassAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.enabledCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandlePointcutWithMultipleMethods() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, MultipleMethodsAdvice.class);
        // when
        test.execute1();
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(2);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(2);
    }

    @Test
    public void shouldNotTryToWeaveNativeMethods() throws Exception {
        // given
        // when
        newWovenObject(NativeMisc.class, Misc.class, BasicAdvice.class);
        // then should not bomb
    }

    @Test
    public void shouldNotTryToWeaveAbstractMethods() throws Exception {
        // given
        Misc test = newWovenObject(ExtendsAbstractMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute1();
        // then should not bomb
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
    public void shouldPayAttentionToStaticModifierMatching() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class, NonMatchingStaticAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(0);
    }

    @Test
    public void shouldPayAttentionToPublicAndNonStaticModifierMatching() throws Exception {
        // given
        Misc test = newWovenObject(BasicMisc.class, Misc.class,
                MatchingPublicNonStaticAdvice.class);
        // when
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onReturnCount.get()).isEqualTo(1);
        assertThat(SomeAspectThreadLocals.onThrowCount.get()).isEqualTo(0);
        assertThat(SomeAspectThreadLocals.onAfterCount.get()).isEqualTo(1);
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
        // when
        newWovenObject(BasicMisc.class, Misc.class, CircularClassDependencyAdvice.class);
        // then should not bomb
    }

    @Test
    // weaving an interface method that appears twice in a given class hierarchy should only weave
    // the method once
    public void shouldHandleInterfaceThatAppearsTwiceInHierarchy() throws Exception {
        // given
        // when
        Misc test = newWovenObject(SubBasicMisc.class, Misc.class,
                InterfaceAppearsTwiceInHierarchyAdvice.class);
        test.execute1();
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    public void shouldHandleFinalMethodAdvice() throws Exception {
        // given
        // when
        Misc test = newWovenObject(SubBasicMisc.class, Misc.class, FinalMethodAdvice.class);
        test.executeWithArgs("one", 2);
        // then
        assertThat(SomeAspectThreadLocals.onBeforeCount.get()).isEqualTo(1);
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames2() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice2.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames3() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice3.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames4() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice4.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames5() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice5.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldWeaveBytecodeWithStackFrames6() throws Exception {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
        Misc test = newWovenObject(BytecodeWithStackFramesMisc.class, Misc.class,
                TestBytecodeWithStackFramesAdvice6.class);
        test.executeWithReturn();
    }

    @Test
    // test weaving against jdk 1.7 bytecode with stack frames
    public void shouldNotBombWithDuplicateFrames() throws Exception {
        // TODO this test only proves something when -target 1.7 (which currently it never is during
        // travis build)
        assumeJdk7();
        newWovenObject(DuplicateStackFramesMisc.class, Misc.class, BasicAdvice.class);
    }

    @Test
    public void shouldNotBombWithTroublesomeBytecode() throws Exception {
        // this actually works with -target 1.6 as long as run using 1.7 jvm since it defines the
        // troublesome bytecode at runtime as jdk 1.7 bytecode
        assumeJdk7();
        Misc test = newWovenObject(TroublesomeBytecodeMisc.class, Misc.class,
                TestTroublesomeBytecodeAdvice.class);
        test.execute1();
    }

    public static <S, T extends S> S newWovenObject(Class<T> implClass, Class<S> bridgeClass,
            Class<?> adviceOrShimOrMixinClass, Class<?>... extraBridgeClasses) throws Exception {

        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        if (adviceOrShimOrMixinClass.isAnnotationPresent(Pointcut.class)) {
            loader.setAdvisors(ImmutableList.of(
                    new AdviceBuilder(adviceOrShimOrMixinClass, false).build()));
        }
        Mixin mixin = adviceOrShimOrMixinClass.getAnnotation(Mixin.class);
        if (mixin != null) {
            loader.setMixinTypes(ImmutableList.of(MixinType.from(mixin, adviceOrShimOrMixinClass)));
        }
        Shim shim = adviceOrShimOrMixinClass.getAnnotation(Shim.class);
        if (shim != null) {
            loader.setShimTypes(ImmutableList.of(ShimType.from(shim, adviceOrShimOrMixinClass)));
        }
        loader.setWeavingTimerService(NopWeavingTimerService.INSTANCE);
        // SomeAspectThreadLocals is passed as bridgeable so that the static thread locals will be
        // accessible for test verification
        loader.addBridgeClasses(bridgeClass, SomeAspectThreadLocals.class,
                IntegerThreadLocal.class);
        loader.addBridgeClasses(extraBridgeClasses);
        return loader.build().newInstance(implClass, bridgeClass);
    }

    private static void assumeJdk7() {
        Assume.assumeFalse(StandardSystemProperty.JAVA_VERSION.value().startsWith("1.6"));
    }

    private static class NopWeavingTimerService implements WeavingTimerService {
        private static final NopWeavingTimerService INSTANCE = new NopWeavingTimerService();
        @Override
        public WeavingTimer start() {
            return NopWeavingTimer.INSTANCE;
        }
    }

    private static class NopWeavingTimer implements WeavingTimer {
        private static final NopWeavingTimer INSTANCE = new NopWeavingTimer();
        @Override
        public void stop() {}
    }
}
