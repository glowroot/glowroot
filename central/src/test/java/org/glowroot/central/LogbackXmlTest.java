/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

public class LogbackXmlTest {

    @TempDir
    Path tempDir;

    @Test
    public void defaultConfigurationAttachesConsoleAndFileWithoutWarnings() throws Exception {
        configureAndAssert(null, null, true, false);
    }

    @Test
    public void consoleOffFalseAttachesConsoleAndFileWithoutWarnings() throws Exception {
        configureAndAssert("false", null, true, false);
    }

    @Test
    public void consoleOffTrueDisablesConsoleCaseInsensitively() throws Exception {
        configureAndAssert("true", null, false, false);
        configureAndAssert("TRUE", null, false, false);
    }

    @Test
    public void auditOnTrueEnablesAuditAppenderCaseInsensitively() throws Exception {
        configureAndAssert(null, "true", true, true);
        configureAndAssert("TRUE", "True", false, true);
    }

    private void configureAndAssert(@Nullable String consoleOff, @Nullable String auditOn,
            boolean expectConsoleAppender, boolean expectAuditEnabled) throws Exception {
        String priorLogDir = System.getProperty("glowroot.log.dir");
        String originalConsoleOff = System.getProperty("glowroot.log.consoleOff");
        String originalAuditOn = System.getProperty("glowroot.log.auditOn");

        setOrClear("glowroot.log.consoleOff", consoleOff);
        setOrClear("glowroot.log.auditOn", auditOn);

        System.setProperty("glowroot.log.dir", tempDir.toString());
        String priorConsoleOff =
                CentralModule.normalizeBooleanSystemProperty("glowroot.log.consoleOff");
        String priorAuditOn =
                CentralModule.normalizeBooleanSystemProperty("glowroot.log.auditOn");

        LoggerContext context = new LoggerContext();
        try {
            URL logbackXml = CentralModule.class.getClassLoader().getResource("logback.xml");
            assertThat(logbackXml).isNotNull();

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(logbackXml);

            List<Status> warningsOrErrors = context.getStatusManager().getCopyOfStatusList()
                    .stream()
                    .filter(status -> status.getLevel() > Status.INFO)
                    .collect(Collectors.toList());
            assertThat(warningsOrErrors).isEmpty();

            Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertThat(rootLogger.getAppender("FILE")).isNotNull();
            if (expectConsoleAppender) {
                assertThat(rootLogger.getAppender("CONSOLE")).isNotNull();
            } else {
                assertThat(rootLogger.getAppender("CONSOLE")).isNull();
            }

            Logger auditLogger = context.getLogger("audit");
            if (expectAuditEnabled) {
                assertThat(auditLogger.getLevel()).isEqualTo(Level.INFO);
                assertThat(auditLogger.isAdditive()).isFalse();
                assertThat(auditLogger.getAppender("AUDIT")).isNotNull();
            } else {
                assertThat(auditLogger.getLevel()).isEqualTo(Level.OFF);
                assertThat(auditLogger.getAppender("AUDIT")).isNull();
            }
        } finally {
            context.stop();
            CentralModule.restoreSystemProperty("glowroot.log.consoleOff", priorConsoleOff);
            CentralModule.restoreSystemProperty("glowroot.log.auditOn", priorAuditOn);
            assertThat(System.getProperty("glowroot.log.consoleOff")).isEqualTo(consoleOff);
            assertThat(System.getProperty("glowroot.log.auditOn")).isEqualTo(auditOn);

            CentralModule.restoreSystemProperty("glowroot.log.dir", priorLogDir);
            CentralModule.restoreSystemProperty("glowroot.log.consoleOff", originalConsoleOff);
            CentralModule.restoreSystemProperty("glowroot.log.auditOn", originalAuditOn);
        }
    }

    private static void setOrClear(String name, @Nullable String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
