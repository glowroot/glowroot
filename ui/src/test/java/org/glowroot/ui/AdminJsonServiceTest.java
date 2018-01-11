/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.ui;

import java.io.File;
import java.net.InetAddress;

import javax.mail.Message;

import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.config.ImmutableEmbeddedAdminGeneralConfig;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.RepoAdmin;
import org.glowroot.common.repo.util.HttpClient;
import org.glowroot.common.repo.util.MailService;
import org.glowroot.ui.AdminJsonService.SmtpConfigDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AdminJsonServiceTest {

    private MockMailService mailService;
    private HttpClient httpClient;
    private AdminJsonService adminJsonService;

    @Before
    public void beforeEachTest() {
        mailService = new MockMailService();
        httpClient = mock(HttpClient.class);
        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getEmbeddedAdminGeneralConfig())
                .thenReturn(ImmutableEmbeddedAdminGeneralConfig.builder().build());
        adminJsonService = new AdminJsonService(false, false, new File("."), null, configRepository,
                mock(RepoAdmin.class), mock(LiveAggregateRepository.class), mailService,
                httpClient);
    }

    @Test
    public void testWithoutDefaults() throws Exception {
        // given
        SmtpConfigDto configDto = ImmutableSmtpConfigDto.builder()
                .host("localhost")
                .username("")
                .passwordExists(false)
                .fromEmailAddress("from@example.org")
                .fromDisplayName("From Example")
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        // when
        adminJsonService.sendTestEmail(configDto);
        // then
        Message message = mailService.getMessage();
        assertThat(message.getFrom()[0].toString()).isEqualTo("From Example <from@example.org>");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("[Glowroot] Test email");
        assertThat(message.getContent()).isEqualTo("");
    }

    @Test
    public void testWithDefaultFrom() throws Exception {
        // given
        SmtpConfigDto configDto = ImmutableSmtpConfigDto.builder()
                .host("localhost")
                .username("")
                .passwordExists(false)
                .fromEmailAddress("")
                .fromDisplayName("From Example")
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        // when
        adminJsonService.sendTestEmail(configDto);
        // then
        Message message = mailService.getMessage();
        String localHostname = InetAddress.getLocalHost().getHostName();
        assertThat(message.getFrom()[0].toString())
                .isEqualTo("From Example <glowroot@" + localHostname + ">");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("[Glowroot] Test email");
        assertThat(message.getContent()).isEqualTo("");
    }

    @Test
    public void testWithDefaultFromAndDefaultDisplayName() throws Exception {
        // given
        SmtpConfigDto configDto = ImmutableSmtpConfigDto.builder()
                .host("localhost")
                .username("")
                .passwordExists(false)
                .fromEmailAddress("")
                .fromDisplayName("")
                .version("1234")
                .testEmailRecipient("to@example.org")
                .build();
        // when
        adminJsonService.sendTestEmail(configDto);
        // then
        Message message = mailService.getMessage();
        String localHostname = InetAddress.getLocalHost().getHostName();
        assertThat(message.getFrom()[0].toString())
                .isEqualTo("Glowroot <glowroot@" + localHostname + ">");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("to@example.org");
        assertThat(message.getSubject()).isEqualTo("[Glowroot] Test email");
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
