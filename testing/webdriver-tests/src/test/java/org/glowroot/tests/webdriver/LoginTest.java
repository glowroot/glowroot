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
package org.glowroot.tests.webdriver;

import org.junit.Test;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.webdriver.config.ConfigSidebar;
import org.glowroot.tests.webdriver.config.UserInterfaceConfigPage;

public class LoginTest extends WebDriverTest {

    @Test
    public void shouldLogin() throws Exception {
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UserInterfaceConfigPage page = new UserInterfaceConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserInterfaceLink().click();
        page.getPasswordEnabledCheckBox().click();
        page.getInitialPasswordTextField().sendKeys("a");
        page.getVerifyInitialPasswordTextField().sendKeys("a");
        page.getSaveButton().click();

        globalNavbar.getSignOutLink().click();
        globalNavbar.getLoginPasswordTextField().sendKeys("a");
        globalNavbar.getLoginButton().click();

        globalNavbar.getSignOutLink().click();
        // F5 to refresh index.html now that un-authenticated, so layout will not be sent back
        // embedded in page (see IndexHtmlHttpService.java)
        driver.navigate().refresh();
        globalNavbar.getLoginPasswordTextField().sendKeys("a");
        globalNavbar.getLoginButton().click();

        // need to take password off before @After otherwise config reset code fails with 401
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserInterfaceLink().click();
        page.getPasswordEnabledCheckBox().click();
        page.getVerifyCurrentPasswordTextField().sendKeys("a");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));
    }
}
