/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.tests.admin;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class RoleConfigPage {

    private final WebDriver driver;

    public RoleConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getNameTextField() {
        return withWait(xpath("//div[@gt-label='Name']//input"));
    }

    public WebElement getTransactionCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction._']"));
    }

    public WebElement getTransactionOverviewCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction.overview']"));
    }

    public WebElement getTransactionTracesCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction.traces']"));
    }

    public WebElement getTransactionQueriesCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction.queries']"));
    }

    public WebElement getTransactionServiceCallsCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction.serviceCalls']"));
    }

    public WebElement getTransactionProfileCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.transaction.profile']"));
    }

    public WebElement getErrorCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.error._']"));
    }

    public WebElement getErrorOverviewCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.error.overview']"));
    }

    public WebElement getErrorTracesCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.error.traces']"));
    }

    public WebElement getJvmCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm._']"));
    }

    public WebElement getJvmGaugesCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.gauges']"));
    }

    public WebElement getJvmThreadDumpCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.threadDump']"));
    }

    public WebElement getJvmHeapDumpCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.heapDump']"));
    }

    public WebElement getJvmHeapHistogramCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.heapHistogram']"));
    }

    public WebElement getJvmMBeanTreeCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.mbeanTree']"));
    }

    public WebElement getJvmSystemPropertiesCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.systemProperties']"));
    }

    public WebElement getJvmEnvironmentCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.jvm.environment']"));
    }

    public WebElement getConfigViewCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.view']"));
    }

    public WebElement getConfigEditCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit._']"));
    }

    public WebElement getConfigEditTransactionCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.transaction']"));
    }

    public WebElement getConfigEditGaugeCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.gauge']"));
    }

    public WebElement getConfigEditAlertCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.alert']"));
    }

    public WebElement getConfigEditUiCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.ui']"));
    }

    public WebElement getConfigEditPluginCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.plugin']"));
    }

    public WebElement getConfigEditInstrumentationCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.instrumentation']"));
    }

    public WebElement getConfigEditAdvancedCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.config.edit.advanced']"));
    }

    public WebElement getAdminCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.admin._']"));
    }

    public WebElement getAdminViewCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.admin.view']"));
    }

    public WebElement getAdminEditCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.admin.edit']"));
    }

    public void clickAddButton() {
        // in central ui, there are 2 "Add" buttons, one is to add an agent specific permission
        // block, the other is to save new role
        clickWithWait(xpath("//div[@gt-click='save(deferred)']//button"));
    }

    public WebElement getDuplicateRoleMessage() {
        return withWait(xpath("//div[text()='There is already a role with this name']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
