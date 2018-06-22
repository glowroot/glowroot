/*
 * Copyright 2016-2018 the original author or authors.
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

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class RoleConfigPage extends Page {

    public RoleConfigPage(WebDriver driver) {
        super(driver);
    }

    public WebElement getNameTextField() {
        return getWithWait(xpath("//div[@gt-label='Name']//input"));
    }

    public void clickTransactionCheckBox() {
        clickPermission("transaction._");
    }

    public boolean getTransactionCheckBoxValue() {
        return getPermission("transaction._");
    }

    public void clickTransactionOverviewCheckBox() {
        clickPermission("transaction.overview");
    }

    public boolean getTransactionOverviewCheckBoxValue() {
        return getPermission("transaction.overview");
    }

    public void clickTransactionTracesCheckBox() {
        clickPermission("transaction.traces");
    }

    public boolean getTransactionTracesCheckBoxValue() {
        return getPermission("transaction.traces");
    }

    public void clickTransactionQueriesCheckBox() {
        clickPermission("transaction.queries");
    }

    public boolean getTransactionQueriesCheckBoxValue() {
        return getPermission("transaction.queries");
    }

    public void clickTransactionServiceCallsCheckBox() {
        clickPermission("transaction.serviceCalls");
    }

    public boolean getTransactionServiceCallsCheckBoxValue() {
        return getPermission("transaction.serviceCalls");
    }

    public void clickTransactionThreadProfileCheckBox() {
        clickPermission("transaction.threadProfile");
    }

    public boolean getTransactionThreadProfileCheckBoxValue() {
        return getPermission("transaction.threadProfile");
    }

    public void clickErrorCheckBox() {
        clickPermission("error._");
    }

    public boolean getErrorCheckBoxValue() {
        return getPermission("error._");
    }

    public void clickErrorOverviewCheckBox() {
        clickPermission("error.overview");
    }

    public boolean getErrorOverviewCheckBoxValue() {
        return getPermission("error.overview");
    }

    public void clickErrorTracesCheckBox() {
        clickPermission("error.traces");
    }

    public boolean getErrorTracesCheckBoxValue() {
        return getPermission("error.traces");
    }

    public void clickJvmCheckBox() {
        clickPermission("jvm._");
    }

    public boolean getJvmCheckBoxValue() {
        return getPermission("jvm._");
    }

    public void clickJvmGaugesCheckBox() {
        clickPermission("jvm.gauges");
    }

    public boolean getJvmGaugesCheckBoxValue() {
        return getPermission("jvm.gauges");
    }

    public void clickJvmThreadDumpCheckBox() {
        clickPermission("jvm.threadDump");
    }

    public boolean getJvmThreadDumpCheckBoxValue() {
        return getPermission("jvm.threadDump");
    }

    public void clickJvmHeapDumpCheckBox() {
        clickPermission("jvm.heapDump");
    }

    public boolean getJvmHeapDumpCheckBoxValue() {
        return getPermission("jvm.heapDump");
    }

    public void clickJvmHeapHistogramCheckBox() {
        clickPermission("jvm.heapHistogram");
    }

    public boolean getJvmHeapHistogramCheckBoxValue() {
        return getPermission("jvm.heapHistogram");
    }

    public void clickJvmMBeanTreeCheckBox() {
        clickPermission("jvm.mbeanTree");
    }

    public boolean getJvmMBeanTreeCheckBoxValue() {
        return getPermission("jvm.mbeanTree");
    }

    public void clickJvmSystemPropertiesCheckBox() {
        clickPermission("jvm.systemProperties");
    }

    public boolean getJvmSystemPropertiesCheckBoxValue() {
        return getPermission("jvm.systemProperties");
    }

    public void clickJvmEnvironmentCheckBox() {
        clickPermission("jvm.environment");
    }

    public boolean getJvmEnvironmentCheckBoxValue() {
        return getPermission("jvm.environment");
    }

    public void clickConfigViewCheckBox() {
        clickPermission("config.view");
    }

    public boolean getConfigViewCheckBoxValue() {
        return getPermission("config.view");
    }

    public void clickConfigEditCheckBox() {
        clickPermission("config.edit._");
    }

    public boolean getConfigEditCheckBoxValue() {
        return getPermission("config.edit._");
    }

    public void clickConfigEditTransactionCheckBox() {
        clickPermission("config.edit.transaction");
    }

    public boolean getConfigEditTransactionCheckBoxValue() {
        return getPermission("config.edit.transaction");
    }

    public void clickConfigEditGaugesCheckBox() {
        clickPermission("config.edit.gauges");
    }

    public boolean getConfigEditGaugesCheckBoxValue() {
        return getPermission("config.edit.gauges");
    }

    public void clickConfigEditJvmCheckBox() {
        clickPermission("config.edit.jvm");
    }

    public boolean getConfigEditJvmCheckBoxValue() {
        return getPermission("config.edit.jvm");
    }

    public void clickConfigEditAlertsCheckBox() {
        clickPermission("config.edit.alerts");
    }

    public boolean getConfigEditAlertsCheckBoxValue() {
        return getPermission("config.edit.alerts");
    }

    public void clickConfigEditUiDefaultsCheckBox() {
        clickPermission("config.edit.uiDefaults");
    }

    public boolean getConfigEditUiDefaultsCheckBoxValue() {
        return getPermission("config.edit.uiDefaults");
    }

    public void clickConfigEditPluginsCheckBox() {
        clickPermission("config.edit.plugins");
    }

    public boolean getConfigEditPluginsCheckBoxValue() {
        return getPermission("config.edit.plugins");
    }

    public void clickConfigEditInstrumentationCheckBox() {
        clickPermission("config.edit.instrumentation");
    }

    public boolean getConfigEditInstrumentationCheckBoxValue() {
        return getPermission("config.edit.instrumentation");
    }

    public void clickConfigEditAdvancedCheckBox() {
        clickPermission("config.edit.advanced");
    }

    public boolean getConfigEditAdvancedCheckBoxValue() {
        return getPermission("config.edit.advanced");
    }

    public void clickAdminCheckBox() {
        clickPermission("admin._");
    }

    public boolean getAdminCheckBoxValue() {
        return getPermission("admin._");
    }

    public void clickAdminViewCheckBox() {
        clickPermission("admin.view");
    }

    public boolean getAdminViewCheckBoxValue() {
        return getPermission("admin.view");
    }

    public void clickAdminEditCheckBox() {
        clickPermission("admin.edit");
    }

    public boolean getAdminEditCheckBoxValue() {
        return getPermission("admin.edit");
    }

    public void clickAddButton() {
        // in central ui, there are 2 "Add" buttons, one is to add an agent specific permission
        // block, the other is to save new role
        clickWithWait(xpath("//div[@gt-click='save(deferred)']//button"));
    }

    public WebElement getDuplicateRoleMessage() {
        return getWithWait(xpath("//div[text()='There is already a role with this name']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public void clickDeleteButton() {
        clickWithWait(xpath("//button[normalize-space()='Delete']"));
    }

    public void waitForDeleteButton() {
        waitFor(xpath("//button[normalize-space()='Delete']"));
    }

    private void clickPermission(String permission) {
        clickWithWait(xpath("//input[@ng-model='page.permissions." + permission + "']/.."));
    }

    private boolean getPermission(String permission) {
        return getWithWait(xpath("//input[@ng-model='page.permissions." + permission + "']"))
                .isSelected();
    }
}
