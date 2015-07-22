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
package org.glowroot.local.ui;

import java.net.InetAddress;

import javax.mail.Message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.store.MailService;
import org.glowroot.transaction.TransactionModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ConfigJsonServiceTest {

    private ConfigService configService;
    private CappedDatabase cappedDatabase;
    private HttpSessionManager httpSessionManager;
    private TransactionModule transactionModule;
    private MockMailService mailService;
    private ConfigJsonService configJsonService;

    @Before
    public void beforeEachTest() {
        configService = mock(ConfigService.class);
        cappedDatabase = mock(CappedDatabase.class);
        httpSessionManager = mock(HttpSessionManager.class);
        transactionModule = mock(TransactionModule.class);
        mailService = new MockMailService();
        configJsonService = new ConfigJsonService(configService, ImmutableList.<CappedDatabase>of(),
                cappedDatabase, ImmutableList.<PluginDescriptor>of(), httpSessionManager,
                transactionModule, mailService);
    }

    @Test
    public void testWithoutDefaults() throws Exception {
        // given
        SmtpConfigDto configDto = SmtpConfigDto.builder()
                .fromEmailAddress("from@example.org")
                .fromDisplayName("From Example")
                .host("localhost")
                .ssl(false)
                .username("")
                .passwordExists(false)
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        String content = new ObjectMapper().writeValueAsString(configDto);
        // when
        configJsonService.sendTestEmail(content);
        // then
        Message message = mailService.getMessage();
        assertThat(message.getFrom()[0].toString()).isEqualTo("From Example <from@example.org>");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("Test email from Glowroot (EOM)");
        assertThat(message.getContent()).isEqualTo("");
    }

    @Test
    public void testWithDefaultFrom() throws Exception {
        // given
        SmtpConfigDto configDto = SmtpConfigDto.builder()
                .fromEmailAddress("")
                .fromDisplayName("From Example")
                .host("localhost")
                .ssl(false)
                .username("")
                .passwordExists(false)
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        String content = new ObjectMapper().writeValueAsString(configDto);
        // when
        configJsonService.sendTestEmail(content);
        // then
        Message message = mailService.getMessage();
        String localHostname = InetAddress.getLocalHost().getHostName();
        assertThat(message.getFrom()[0].toString())
                .isEqualTo("From Example <glowroot@" + localHostname + ">");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("Test email from Glowroot (EOM)");
        assertThat(message.getContent()).isEqualTo("");
    }

    @Test
    public void testWithDefaultFromAndDefaultDisplayName() throws Exception {
        // given
        SmtpConfigDto configDto = SmtpConfigDto.builder()
                .fromEmailAddress("")
                .fromDisplayName("")
                .host("localhost")
                .ssl(false)
                .username("")
                .passwordExists(false)
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        String content = new ObjectMapper().writeValueAsString(configDto);
        // when
        configJsonService.sendTestEmail(content);
        // then
        Message message = mailService.getMessage();
        String localHostname = InetAddress.getLocalHost().getHostName();
        assertThat(message.getFrom()[0].toString())
                .isEqualTo("Glowroot <glowroot@" + localHostname + ">");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("Test email from Glowroot (EOM)");
        assertThat(message.getContent()).isEqualTo("");
    }

    static class MockMailService extends MailService {

        private Message msg;

        @Override
        public void send(Message msg) {
            this.msg = msg;
        }

        public Message getMessage() {
            return msg;
        }
    }
}
