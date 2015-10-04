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
package org.glowroot.agent.jul;

// the use of java.util.logging is changed during shading to use org.glowroot.agent.jul instead
// so that shaded libraries that use java.util.logging (e.g. shaded guava), will be redirected to
// use the glowroot (shaded) sl4j logger instead
public class Level {

    public static final Level OFF = new Level("OFF", Integer.MAX_VALUE);
    public static final Level SEVERE = new Level("SEVERE", 1000);
    public static final Level WARNING = new Level("WARNING", 900);
    public static final Level INFO = new Level("INFO", 800);
    public static final Level CONFIG = new Level("CONFIG", 700);
    public static final Level FINE = new Level("FINE", 500);
    public static final Level FINER = new Level("FINER", 400);
    public static final Level FINEST = new Level("FINEST", 300);
    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE);

    private final String name;
    private final int value;

    public Level(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public int intValue() {
        return value;
    }
}
