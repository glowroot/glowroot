/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.tests;

@SuppressWarnings("unused")
class CoverAllTypes {

    void getVoid() {}

    boolean getBoolean() {
        return false;
    }

    byte getByte() {
        return 100;
    }

    char getChar() {
        return 'b';
    }

    short getShort() {
        return 300;
    }

    int getInt() {
        return 400;
    }

    long getLong() {
        return 500;
    }

    float getFloat() {
        return 600;
    }

    double getDouble() {
        return 700;
    }

    int[] getArray() {
        return new int[] {1, 2, 3};
    }

    void putBoolean(boolean value) {}

    void putByte(byte value) {}

    void putChar(char value) {}

    void putShort(short value) {}

    void putInt(int value) {}

    void putLong(long value) {}

    void putFloat(float value) {}

    void putDouble(double value) {}

    void putArray(int[] value) {}
}
