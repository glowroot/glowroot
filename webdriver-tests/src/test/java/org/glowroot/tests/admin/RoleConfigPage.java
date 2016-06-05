/*
 * Copyright 2016 the original author or authors.
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

import org.glowroot.tests.Utils;

import static org.openqa.selenium.By.xpath;

public class RoleConfigPage {

    private final WebDriver driver;

    public RoleConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getNameTextField() {
        return withWait(xpath("//div[@gt-label='Name']//input"));
    }

    public WebElement getViewCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.view']"));
    }

    public WebElement getToolCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.tool._']"));
    }

    public WebElement getToolThreadDumpCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.tool.threadDump']"));
    }

    public WebElement getToolHeapDumpCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.tool.heapDump']"));
    }

    public WebElement getToolMBeanTreeCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.tool.mbeanTree']"));
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

    public WebElement getAdministrationCheckBox() {
        return withWait(xpath("//input[@ng-model='page.permissions.admin']"));
    }

    public WebElement getAddButton() {
        // on server, there are 2 "Add" buttons, one is to add an agent specific permission block,
        // the other is to save new role
        return withWait(xpath("//div[@gt-click='save(deferred)']//button"));
    }

    public WebElement getDuplicateRoleMessage() {
        return withWait(xpath("//div[text()='There is already a role with this name']"));
    }

    public void clickSaveButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Save changes']"));
        saveButton.click();
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
