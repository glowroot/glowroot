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

import javax.annotation.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutConfig {

    private List<CaptureItem> captureItems;
    private String typeName;
    private String methodName;
    private List<String> methodArgTypeNames;
    private String methodReturnTypeName;
    private List<MethodModifier> methodModifiers;
    @Nullable
    private String metricName;
    @Nullable
    private String spanTemplate;

    public List<CaptureItem> getCaptureItems() {
        return captureItems;
    }

    public void setCaptureItems(List<CaptureItem> captureItems) {
        this.captureItems = captureItems;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public List<String> getMethodArgTypeNames() {
        return methodArgTypeNames;
    }

    public void setMethodArgTypeNames(List<String> methodArgTypeNames) {
        this.methodArgTypeNames = methodArgTypeNames;
    }

    public String getMethodReturnTypeName() {
        return methodReturnTypeName;
    }

    public void setMethodReturnTypeName(String methodReturnTypeName) {
        this.methodReturnTypeName = methodReturnTypeName;
    }

    public List<MethodModifier> getMethodModifiers() {
        return methodModifiers;
    }

    public void setMethodModifiers(List<MethodModifier> methodModifiers) {
        this.methodModifiers = methodModifiers;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public String getSpanTemplate() {
        return spanTemplate;
    }

    public void setSpanTemplate(String spanTemplate) {
        this.spanTemplate = spanTemplate;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PointcutConfig) {
            PointcutConfig that = (PointcutConfig) obj;
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
