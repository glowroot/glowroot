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
package org.glowroot.agent.tests.app;

@SuppressWarnings("unused")
public class CoverAllTypes {

    void getVoid() {}

    public boolean getBoolean() {
        return false;
    }

    public byte getByte() {
        return 100;
    }

    public char getChar() {
        return 'b';
    }

    public short getShort() {
        return 300;
    }

    public int getInt() {
        return 400;
    }

    public long getLong() {
        return 500;
    }

    public float getFloat() {
        return 600;
    }

    public double getDouble() {
        return 700;
    }

    public int[] getArray() {
        return new int[] {1, 2, 3};
    }

    public void putBoolean(boolean value) {}

    public void putByte(byte value) {}

    public void putChar(char value) {}

    public void putShort(short value) {}

    public void putInt(int value) {}

    public void putLong(long value) {}

    public void putFloat(float value) {}

    public void putDouble(double value) {}

    public void putArray(int[] value) {}
}
