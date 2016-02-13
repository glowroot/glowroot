/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.webdriver.tests;

import java.util.List;

import com.google.common.base.Function;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import static org.openqa.selenium.By.xpath;

class GlobalNavbar {

    private final WebDriver driver;

    GlobalNavbar(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getTransactionsLink() {
        return getNavbarLink(driver, xpath("//nav//a[normalize-space()='Transactions']"));
    }

    WebElement getErrorsLink() {
        return getNavbarLink(driver, xpath("//nav//a[normalize-space()='Errors']"));
    }

    WebElement getJvmLink() {
        return getNavbarLink(driver, xpath("//nav//a[normalize-space()='JVM']"));
    }

    WebElement getConfigurationLink() {
        return getNavbarLink(driver, xpath("//nav//a[@href='config/transaction']"));
    }

    WebElement getSignOutLink() {
        return getNavbarLink(driver, xpath("//nav//a[@ng-click='signOut()']"));
    }

    WebElement getLoginPasswordTextField() {
        return Utils.withWait(driver, xpath("//input[@ng-model='page.password']"));
    }

    WebElement getLoginButton() {
        return Utils.withWait(driver, xpath("//div[@gt-label='Login']//button"));
    }

    public static WebElement getNavbarLink(final WebDriver driver, final By by) {
        return new WebDriverWait(driver, 30).until(new Function<WebDriver, WebElement>() {
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
                        driver.findElements(By.className("gt-panel-overlay"));
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
                        driver.findElements(By.cssSelector("button.navbar-toggle"));
                if (!navbarToggleElements.isEmpty() && navbarToggleElements.get(0).isDisplayed()) {
                    navbarToggleElements.get(0).click();
                }
            }
        });
    }
}
