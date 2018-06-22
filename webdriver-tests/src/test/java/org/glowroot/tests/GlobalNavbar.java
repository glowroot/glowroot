/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.tests;

import java.util.List;
import java.util.function.Function;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.util.Page;
import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.className;
import static org.openqa.selenium.By.cssSelector;
import static org.openqa.selenium.By.id;
import static org.openqa.selenium.By.xpath;

class GlobalNavbar extends Page {

    GlobalNavbar(WebDriver driver) {
        super(driver);
    }

    void clickTransactionsLink() {
        clickNavbarLink(Utils.linkText("Transactions"));
    }

    void clickErrorsLink() {
        clickNavbarLink(Utils.linkText("Errors"));
    }

    void clickJvmLink() {
        clickNavbarLink(Utils.linkText("JVM"));
    }

    void clickReportingLink() {
        clickNavbarLink(Utils.linkText("Reporting"));
    }

    void clickConfigLink() {
        clickNavbarLink(id("gtGearsMenuToggle"));
        WebElement gearsMenu = getWithWait(id("gtGearsMenu"));
        clickLinkWithWait(gearsMenu, "Configuration");
    }

    void clickAdminConfigLink() {
        clickNavbarLink(id("gtGearsMenuToggle"));
        WebElement gearsMenu = getWithWait(id("gtGearsMenu"));
        clickLinkWithWait(gearsMenu, "Administration");
    }

    void clickChangeMyPasswordLink() {
        clickNavbarLink(id("gtGearsMenuToggle"));
        WebElement gearsMenu = getWithWait(id("gtGearsMenu"));
        clickLinkWithWait(gearsMenu, "Change my password");
    }

    void clickSignInLink() {
        clickNavbarLink(id("gtGearsMenuToggle"));
        WebElement gearsMenu = getWithWait(id("gtGearsMenu"));
        clickLinkWithWait(gearsMenu, "Login");
    }

    void clickSignOutLink() {
        clickNavbarLink(xpath("//nav//button[@ng-click='signOut()']"));
        // wait for sign in link to appear
        clickNavbarLink(id("gtGearsMenuToggle"));
        WebElement gearsMenu = getWithWait(id("gtGearsMenu"));
        waitFor(gearsMenu, Utils.linkText("Login"));
        // close the menu back up
        clickWithWait(id("gtGearsMenuToggle"));
    }

    WebElement getLoginUsernameTextField() {
        return getWithWait(xpath("//input[@ng-model='page.username']"));
    }

    WebElement getLoginPasswordTextField() {
        return getWithWait(xpath("//input[@ng-model='page.password']"));
    }

    void clickLoginButton() {
        clickWithWait(xpath("//div[@gt-label='Login']//button"));
        // wait for sign out button to appear, means login success
        getNavbarLink(driver, xpath("//nav//button[@ng-click='signOut()']"));
    }

    private void clickNavbarLink(By by) {
        Utils.click(driver, getNavbarLink(driver, by));
    }

    private static WebElement getNavbarLink(final WebDriver driver, final By by) {
        // scrolling to top seems to be needed for Edge
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0,0)");
        return new WebDriverWait(driver, 60).until(new Function<WebDriver, WebElement>() {
            @Override
            public WebElement apply(WebDriver driver) {
                List<WebElement> elements = driver.findElements(by);
                if (elements.isEmpty()) {
                    openNavbar();
                    return null;
                }
                WebElement element = elements.get(0);
                try {
                    if (!element.isDisplayed()) {
                        openNavbar();
                        return null;
                    }
                } catch (StaleElementReferenceException e) {
                    // dom was updated in between findElements() and isDisplayed()
                    return null;
                }
                List<WebElement> overlayElements =
                        driver.findElements(className("gt-panel-overlay"));
                for (WebElement overlayElement : overlayElements) {
                    try {
                        if (overlayElement.isDisplayed()) {
                            return null;
                        }
                    } catch (StaleElementReferenceException e) {
                        // dom was updated in between findElements() and isDisplayed()
                        return null;
                    }
                }
                return element;
            }
            private void openNavbar() {
                List<WebElement> navbarToggleElements =
                        driver.findElements(cssSelector("button.navbar-toggle"));
                if (!navbarToggleElements.isEmpty() && navbarToggleElements.get(0).isDisplayed()) {
                    navbarToggleElements.get(0).click();
                }
            }
        });
    }
}
