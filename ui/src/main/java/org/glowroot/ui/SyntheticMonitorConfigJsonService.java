/*
 * Copyright 2014-2017 the original author or authors.
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

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;

import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Compilations.CompilationException;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.repo.util.LazySecretKey;
import org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig.SyntheticMonitorKind;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class SyntheticMonitorConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Pattern encryptPattern = Pattern.compile("\"ENCRYPT:([^\"]*)\"");

    @VisibleForTesting
    static final Ordering<SyntheticMonitorConfig> orderingByDisplay =
            new Ordering<SyntheticMonitorConfig>() {
                @Override
                public int compare(SyntheticMonitorConfig left, SyntheticMonitorConfig right) {
                    return ConfigDefaults.getDisplayOrDefault(left)
                            .compareToIgnoreCase(ConfigDefaults.getDisplayOrDefault(right));
                }
            };

    private final ConfigRepository configRepository;

    SyntheticMonitorConfigJsonService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    // central supports synthetic monitor configs on rollups
    @GET(path = "/backend/config/synthetic-monitors",
            permission = "agent:config:view:syntheticMonitor")
    String getSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigRequest request) throws Exception {
        Optional<String> id = request.id();
        if (id.isPresent()) {
            SyntheticMonitorConfig config =
                    configRepository.getSyntheticMonitorConfig(agentRollupId, id.get());
            if (config == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
        } else {
            List<SyntheticMonitorConfigListDto> configDtos = Lists.newArrayList();
            List<SyntheticMonitorConfig> configs =
                    configRepository.getSyntheticMonitorConfigs(agentRollupId);
            configs = orderingByDisplay.immutableSortedCopy(configs);
            for (SyntheticMonitorConfig config : configs) {
                configDtos.add(SyntheticMonitorConfigListDto.create(config));
            }
            return mapper.writeValueAsString(configDtos);
        }
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/add",
            permission = "agent:config:edit:syntheticMonitor")
    String addSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigDto configDto)
            throws Exception {
        SyntheticMonitorConfig config;
        try {
            config = configDto.convert(configRepository.getLazySecretKey());
        } catch (SymmetricEncryptionKeyMissingException e) {
            return "{\"symmetricEncryptionKeyMissing\":true}";
        }
        String errorResponse = validate(config);
        if (errorResponse != null) {
            return errorResponse;
        }
        String id = configRepository.insertSyntheticMonitorConfig(agentRollupId, config);
        config = config.toBuilder()
                .setId(id)
                .build();
        return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/update",
            permission = "agent:config:edit:syntheticMonitor")
    String updateSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigDto configDto) throws Exception {
        SyntheticMonitorConfig config;
        try {
            config = configDto.convert(configRepository.getLazySecretKey());
        } catch (SymmetricEncryptionKeyMissingException e) {
            return "{\"symmetricEncryptionKeyMissing\":true}";
        }
        String errorResponse = validate(config);
        if (errorResponse != null) {
            return errorResponse;
        }
        configRepository.updateSyntheticMonitorConfig(agentRollupId, config,
                configDto.version().get());
        return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/remove",
            permission = "agent:config:edit:syntheticMonitor")
    void removeSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigRequest request) throws Exception {
        configRepository.deleteSyntheticMonitorConfig(agentRollupId, request.id().get());
    }

    private static @Nullable String validate(SyntheticMonitorConfig config) throws Exception {
        if (config.getKind() == SyntheticMonitorKind.JAVA) {
            // only used by central
            Class<?> javaSource;
            try {
                javaSource = Compilations.compile(config.getJavaSource());
            } catch (CompilationException e) {
                return buildCompilationErrorResponse(e.getCompilationErrors());
            }
            try {
                javaSource.getConstructor();
            } catch (NoSuchMethodException e) {
                return buildCompilationErrorResponse(
                        ImmutableList.of("Class must have a public default constructor"));
            }
            // since synthetic monitors are only used in central, this class is present
            Class<?> webDriverClass = Class.forName("org.openqa.selenium.WebDriver");
            try {
                javaSource.getMethod("test", webDriverClass);
            } catch (NoSuchMethodException e) {
                return buildCompilationErrorResponse(ImmutableList.of("Class must have a"
                        + " \"public void test(WebDriver driver) { ... }\" method"));
            }
        }
        return null;
    }

    private static String buildCompilationErrorResponse(List<String> compilationErrors)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeArrayFieldStart("javaSourceCompilationErrors");
            for (String compilationError : compilationErrors) {
                jg.writeString(compilationError);
            }
            jg.writeEndArray();
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    @Value.Immutable
    interface SyntheticMonitorConfigRequest {
        Optional<String> id();
    }

    @Value.Immutable
    abstract static class SyntheticMonitorConfigListDto {

        abstract String display();
        abstract String id();

        private static SyntheticMonitorConfigListDto create(SyntheticMonitorConfig config) {
            return ImmutableSyntheticMonitorConfigListDto.builder()
                    .display(ConfigDefaults.getDisplayOrDefault(config))
                    .id(config.getId())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SyntheticMonitorConfigDto {

        abstract SyntheticMonitorKind kind();
        abstract @Nullable String display();
        abstract @Nullable String pingUrl();
        abstract @Nullable String javaSource();
        abstract Optional<String> id(); // absent for insert operations
        abstract Optional<String> version(); // absent for insert operations

        private SyntheticMonitorConfig convert(LazySecretKey lazySecretKey) throws Exception {
            SyntheticMonitorConfig.Builder builder = SyntheticMonitorConfig.newBuilder()
                    .setDisplay(Strings.nullToEmpty(display()))
                    .setKind(kind());
            String pingUrl = pingUrl();
            if (pingUrl != null) {
                builder.setPingUrl(pingUrl);
            }
            String javaSource = javaSource();
            if (javaSource != null) {
                Matcher matcher = encryptPattern.matcher(javaSource);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String unencryptedPassword = checkNotNull(matcher.group(1));
                    matcher.appendReplacement(sb, "\"ENCRYPTED:"
                            + Encryption.encrypt(unencryptedPassword, lazySecretKey) + "\"");
                }
                matcher.appendTail(sb);
                builder.setJavaSource(sb.toString());
            }
            Optional<String> id = id();
            if (id.isPresent()) {
                builder.setId(id.get());
            }
            return builder.build();
        }

        private static SyntheticMonitorConfigDto create(SyntheticMonitorConfig config) {
            ImmutableSyntheticMonitorConfigDto.Builder builder =
                    ImmutableSyntheticMonitorConfigDto.builder()
                            .display(config.getDisplay())
                            .kind(config.getKind());
            String pingUrl = config.getPingUrl();
            if (!pingUrl.isEmpty()) {
                builder.pingUrl(pingUrl);
            }
            String javaSource = config.getJavaSource();
            if (!javaSource.isEmpty()) {
                builder.javaSource(javaSource);
            }
            return builder.id(config.getId())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }
}
