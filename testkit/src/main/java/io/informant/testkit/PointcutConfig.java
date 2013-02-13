/**
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
package io.informant.testkit;

import java.util.List;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfig {

    @Nullable
    private List<CaptureItem> captureItems;
    @Nullable
    private String typeName;
    @Nullable
    private String methodName;
    @Nullable
    private List<String> methodArgTypeNames;
    @Nullable
    private String methodReturnTypeName;
    @Nullable
    private List<MethodModifier> methodModifiers;
    @Nullable
    private String metricName;
    @Nullable
    private String spanTemplate;
    @Nullable
    private String versionHash;

    @Nullable
    public List<CaptureItem> getCaptureItems() {
        return captureItems;
    }

    public void setCaptureItems(List<CaptureItem> captureItems) {
        this.captureItems = captureItems;
    }

    @Nullable
    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    @Nullable
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    @Nullable
    public List<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public void setMethodArgTypeNames(List<String> methodArgTypeNames) {
        this.methodArgTypeNames = methodArgTypeNames;
    }

    @Nullable
    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    public void setMethodReturnTypeName(String methodReturnTypeName) {
        this.methodReturnTypeName = methodReturnTypeName;
    }

    @Nullable
    public List<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public void setMethodModifiers(List<MethodModifier> methodModifiers) {
        this.methodModifiers = methodModifiers;
    }

    @Nullable
    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(@Nullable String metricName) {
        this.metricName = metricName;
    }

    @Nullable
    public String getSpanTemplate() {
        return spanTemplate;
    }

    public void setSpanTemplate(@Nullable String spanTemplate) {
        this.spanTemplate = spanTemplate;
    }

    @Nullable
    public String getVersionHash() {
        return versionHash;
    }

    public void setVersionHash(String versionHash) {
        this.versionHash = versionHash;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PointcutConfig) {
            PointcutConfig that = (PointcutConfig) obj;
            // intentionally leaving off versionHash since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(captureItems, that.captureItems)
                    && Objects.equal(typeName, that.typeName)
                    && Objects.equal(methodName, that.methodName)
                    && Objects.equal(methodArgTypeNames, that.methodArgTypeNames)
                    && Objects.equal(methodReturnTypeName, that.methodReturnTypeName)
                    && Objects.equal(methodModifiers, that.methodModifiers)
                    && Objects.equal(metricName, that.metricName)
                    && Objects.equal(spanTemplate, that.spanTemplate);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off versionHash since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(captureItems, typeName, methodName, methodArgTypeNames,
                methodReturnTypeName, methodModifiers, metricName, spanTemplate);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("captureItems", captureItems)
                .add("typeName", typeName)
                .add("methodName", methodName)
                .add("methodArgTypeNames", methodArgTypeNames)
                .add("methodReturnTypeName", methodReturnTypeName)
                .add("methodModifiers", methodModifiers)
                .add("metricName", metricName)
                .add("spanTemplate", spanTemplate)
                .toString();
    }

    public enum CaptureItem {
        METRIC, SPAN, TRACE;
    }

    public enum MethodModifier {
        PUBLIC, PRIVATE, PROTECTED, PACKAGE_PRIVATE, STATIC, NOT_STATIC;
    }
}
