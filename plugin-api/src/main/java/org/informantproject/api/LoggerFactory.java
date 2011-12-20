/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.api;

/**
 * This is simply a wrapper of the SLF4J Logger API without the Marker support.
 * 
 * Currently, Informant uses (a shaded version of) Logback as its SLF4J binding. In the future,
 * however, it may use a custom SLF4J binding to store error messages in its embedded H2 database so
 * that any error messages can be displayed in the embedded UI.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LoggerFactory {

    private static final String ILOGGER_FACTORY_CLASS_NAME =
            "org.informantproject.LoggerFactoryImpl";

    private static final ILoggerFactory iLoggerFactory;

    static {
        try {
            iLoggerFactory = (ILoggerFactory) Class.forName(ILOGGER_FACTORY_CLASS_NAME)
                    .newInstance();
        } catch (InstantiationException e) {
            // this really really really shouldn't happen, but anyways,
            // couldn't load the logger so best recourse at this point is to write error to stderr
            e.printStackTrace();
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            // this really really really shouldn't happen, but anyways,
            // couldn't load the logger so best recourse at this point is to write error to stderr
            e.printStackTrace();
            throw new IllegalStateException(e);
        } catch (ClassNotFoundException e) {
            // this really really really shouldn't happen, but anyways,
            // couldn't load the logger so best recourse at this point is to write error to stderr
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    private LoggerFactory() {}

    public static Logger getLogger(Class<?> clazz) {
        return iLoggerFactory.getLogger(clazz.getName());
    }

    public interface ILoggerFactory {
        Logger getLogger(String name);
    }
}
