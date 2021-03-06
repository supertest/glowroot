/*
 * Copyright 2015-2016 the original author or authors.
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

public class SmtpConfigPage {

    private final WebDriver driver;

    public SmtpConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getFromEmailAddressTextField() {
        return withWait(xpath("//div[@gt-label='From email address']//input"));
    }

    public WebElement getFromDisplayNameTextField() {
        return withWait(xpath("//div[@gt-label='From display name']//input"));
    }

    public WebElement getSmtpHostTextField() {
        return withWait(xpath("//div[@gt-label='SMTP host']//input"));
    }

    public WebElement getSmtpPortTextField() {
        return withWait(xpath("//div[@gt-label='SMTP port']//input"));
    }

    public WebElement getUseSslCheckbox() {
        return withWait(xpath("//div[@gt-label='Use SSL']//input"));
    }

    public WebElement getUsernameTextField() {
        return withWait(xpath("//div[@gt-label='Username']//input"));
    }

    public WebElement getPasswordTextField() {
        return withWait(xpath("//input[@ng-model='password']"));
    }

    public void clickSaveButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Save changes']"));
        saveButton.click();
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
