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
package io.informant.jvm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Flags {

    private static final Logger logger = LoggerFactory.getLogger(Flags.class);

    private static final ImmutableList<String> flagNames;
    private static final boolean supported;
    private static final String unsupportedReason;

    static {
        Class<?> flagClass = null;
        Method getAllFlagsMethod = null;
        try {
            flagClass = Class.forName("sun.management.Flag");
            getAllFlagsMethod = flagClass.getDeclaredMethod("getAllFlags");
            getAllFlagsMethod.setAccessible(true);
        } catch (ClassNotFoundException e) {
            // this is ok, just means its not available
        } catch (NoSuchMethodException e) {
            // this is ok, just means its not available
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
        }
        if (getAllFlagsMethod == null) {
            flagNames = ImmutableList.of();
            supported = false;
            unsupportedReason = "Cannot find class sun.management.Flag";
        } else {
            ImmutableList<String> flagNamesLocal = null;
            try {
                flagNamesLocal = buildFlagNames(flagClass, getAllFlagsMethod);
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            } catch (SecurityException e) {
                logger.error(e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                logger.error(e.getMessage(), e);
            } catch (NoSuchMethodException e) {
                logger.error(e.getMessage(), e);
            } catch (IllegalAccessException e) {
                logger.error(e.getMessage(), e);
            } catch (InvocationTargetException e) {
                logger.error(e.getMessage(), e);
            }
            if (flagNamesLocal == null) {
                flagNames = null;
                supported = false;
                unsupportedReason = "Unsupported due to error, see Informant log";
            } else {
                flagNames = flagNamesLocal;
                supported = true;
                unsupportedReason = "";
            }
        }
    }

    private Flags() {}

    public static Availability getAvailability() {
        return Availability.from(supported, unsupportedReason);
    }

    public static ImmutableList<String> getFlagNames() {
        return flagNames;
    }

    private static ImmutableList<String> buildFlagNames(Class<?> flagClass,
            Method getAllFlagsMethod) throws ClassNotFoundException, SecurityException,
            NoSuchMethodException, IllegalArgumentException, IllegalAccessException,
            InvocationTargetException {

        Class<?> vmOptionClass = Class.forName("com.sun.management.VMOption");
        Method getVMOptionMethod = flagClass.getDeclaredMethod("getVMOption");
        getVMOptionMethod.setAccessible(true);
        Method getNameMethod = vmOptionClass.getDeclaredMethod("getName");
        getNameMethod.setAccessible(true);

        List<?> flags = (List<?>) getAllFlagsMethod.invoke(null);
        ImmutableList.Builder<String> names = ImmutableList.builder();
        for (Object flag : flags) {
            names.add((String) getNameMethod.invoke(getVMOptionMethod.invoke(flag)));
        }
        return names.build();
    }
}
