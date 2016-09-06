/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package org.glowroot.agent.plugin.logger;

// copied form ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator
class LoggerNameAbbreviator {

    private static final int MAX_DOTS = 16;

    private final int targetLength;

    LoggerNameAbbreviator(int targetLength) {
        this.targetLength = targetLength;
    }

    String abbreviate(String fqClassName) {
        StringBuilder buf = new StringBuilder(targetLength);
        if (fqClassName == null) {
            throw new IllegalArgumentException("Class name may not be null");
        }
        int inLen = fqClassName.length();
        if (inLen < targetLength) {
            return fqClassName;
        }
        int[] dotIndexesArray = new int[MAX_DOTS];
        int[] lengthArray = new int[MAX_DOTS + 1];
        int dotCount = computeDotIndexes(fqClassName, dotIndexesArray);
        // if there are not dots than abbreviation is not possible
        if (dotCount == 0) {
            return fqClassName;
        }
        computeLengthArray(fqClassName, dotIndexesArray, lengthArray, dotCount);
        for (int i = 0; i <= dotCount; i++) {
            if (i == 0) {
                buf.append(fqClassName.substring(0, lengthArray[i] - 1));
            } else {
                buf.append(fqClassName.substring(dotIndexesArray[i - 1],
                        dotIndexesArray[i - 1] + lengthArray[i]));
            }
        }
        return buf.toString();
    }

    private void computeLengthArray(final String className, int[] dotArray, int[] lengthArray,
            int dotCount) {
        int toTrim = className.length() - targetLength;
        int len;
        for (int i = 0; i < dotCount; i++) {
            int previousDotPosition = -1;
            if (i > 0) {
                previousDotPosition = dotArray[i - 1];
            }
            int available = dotArray[i] - previousDotPosition - 1;
            len = (available < 1) ? available : 1;
            if (toTrim > 0) {
                len = (available < 1) ? available : 1;
            } else {
                len = available;
            }
            toTrim -= (available - len);
            lengthArray[i] = len + 1;
        }

        int lastDotIndex = dotCount - 1;
        lengthArray[dotCount] = className.length() - dotArray[lastDotIndex];
    }

    private static int computeDotIndexes(final String className, int[] dotArray) {
        int dotCount = 0;
        int k = 0;
        while (true) {
            // ignore the $ separator in our computations. This is both convenient
            // and sensible.
            k = className.indexOf('.', k);
            if (k != -1 && dotCount < MAX_DOTS) {
                dotArray[dotCount] = k;
                dotCount++;
                k++;
            } else {
                break;
            }
        }
        return dotCount;
    }
}
