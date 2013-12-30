/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import java.lang.reflect.Method;
import java.util.List;

import checkers.igj.quals.Immutable;
import com.google.common.collect.ImmutableList;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;
import org.glowroot.common.Reflections.ReflectiveTargetException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactory;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryException;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryHelper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Flags {

    private final ImmutableList<String> flagNames;

    private Flags(ImmutableList<String> flagNames) {
        this.flagNames = flagNames;
    }

    public ImmutableList<String> getFlagNames() {
        return flagNames;
    }

    static class Factory implements OptionalServiceFactory<Flags> {

        public Flags create() throws OptionalServiceFactoryException {
            Class<?> flagClass = OptionalServiceFactoryHelper.classForName("sun.management.Flag");
            Method getAllFlagsMethod =
                    OptionalServiceFactoryHelper.getDeclaredMethod(flagClass, "getAllFlags");
            getAllFlagsMethod.setAccessible(true);
            ImmutableList<String> flagNamesLocal = buildFlagNames(flagClass, getAllFlagsMethod);
            return new Flags(flagNamesLocal);
        }

        private static ImmutableList<String> buildFlagNames(Class<?> flagClass,
                Method getAllFlagsMethod) throws OptionalServiceFactoryException {
            Class<?> vmOptionClass =
                    OptionalServiceFactoryHelper.classForName("com.sun.management.VMOption");
            Method getVMOptionMethod =
                    OptionalServiceFactoryHelper.getDeclaredMethod(flagClass, "getVMOption");
            getVMOptionMethod.setAccessible(true);
            Method getNameMethod =
                    OptionalServiceFactoryHelper.getDeclaredMethod(vmOptionClass, "getName");
            getNameMethod.setAccessible(true);

            List<?> flags = (List<?>) OptionalServiceFactoryHelper.invokeStatic(getAllFlagsMethod);
            if (flags == null) {
                throw new OptionalServiceFactoryException(
                        "Method sun.management.Flag.getAllFlags() returned null");
            }
            ImmutableList.Builder<String> names = ImmutableList.builder();
            for (Object flag : flags) {
                Object option;
                try {
                    option = Reflections.invoke(getVMOptionMethod, flag);
                } catch (ReflectiveTargetException e) {
                    if (e.getCause() instanceof NullPointerException) {
                        // https://bugs.openjdk.java.net/browse/JDK-6658779
                        throw new OptionalServiceFactoryException("Unavailable due to known JDK"
                                + " bug, see https://bugs.openjdk.java.net/browse/JDK-6658779");
                    } else {
                        throw new OptionalServiceFactoryException(e);
                    }
                } catch (ReflectiveException e) {
                    throw new OptionalServiceFactoryException(e);
                }
                if (option == null) {
                    throw new OptionalServiceFactoryException(
                            "Method sun.management.Flag.getVMOption() returned null");
                }
                String name = (String) OptionalServiceFactoryHelper.invoke(getNameMethod, option);
                if (name == null) {
                    throw new OptionalServiceFactoryException(
                            "Method com.sun.management.VMOption.getName() returned null");
                }
                names.add(name);
            }
            return names.build();
        }
    }
}
