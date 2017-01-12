/*
 * Copyright 2016-2017 the original author or authors.
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

import java.security.GeneralSecurityException;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.CannotDeleteLastUserException;
import org.glowroot.common.repo.ConfigRepository.DuplicateUsernameException;
import org.glowroot.common.repo.ConfigRepository.UserNotFoundException;
import org.glowroot.common.util.ObjectMappers;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class UserConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(UserConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<UserConfig> orderingByName = new Ordering<UserConfig>() {
        @Override
        public int compare(UserConfig left, UserConfig right) {
            return left.username().compareToIgnoreCase(right.username());
        }
    };

    private final ConfigRepository configRepository;

    UserConfigJsonService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @GET(path = "/backend/admin/users", permission = "admin:view:user")
    String getUserConfig(@BindRequest UserConfigRequest request) throws Exception {
        Optional<String> username = request.username();
        if (username.isPresent()) {
            return getUserConfigInternal(username.get());
        } else {
            List<UserConfigDto> responses = Lists.newArrayList();
            List<UserConfig> userConfigs = configRepository.getUserConfigs();
            userConfigs = orderingByName.immutableSortedCopy(userConfigs);
            for (UserConfig userConfig : userConfigs) {
                responses.add(UserConfigDto.create(userConfig));
            }
            return mapper.writeValueAsString(responses);
        }
    }

    @GET(path = "/backend/admin/all-role-names", permission = "admin:edit:user")
    String getAllRoleNames() throws Exception {
        return mapper.writeValueAsString(ImmutableAllRolesResponse.builder()
                .allRoles(getAllRoleNamesInternal())
                .ldapAvailable(!configRepository.getLdapConfig().url().isEmpty())
                .build());
    }

    @POST(path = "/backend/admin/users/add", permission = "admin:edit:user")
    String addUser(@BindRequest UserConfigDto userConfigDto) throws Exception {
        UserConfig userConfig = userConfigDto.convert(null);
        try {
            configRepository.insertUserConfig(userConfig);
        } catch (DuplicateUsernameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "username");
        }
        return getUserConfigInternal(userConfig.username());
    }

    @POST(path = "/backend/admin/users/update", permission = "admin:edit:user")
    String updateUser(@BindRequest UserConfigDto userConfigDto) throws Exception {
        UserConfig existingUserConfig = configRepository.getUserConfig(userConfigDto.username());
        if (existingUserConfig == null) {
            throw new UserNotFoundException();
        }
        UserConfig userConfig = userConfigDto.convert(existingUserConfig);
        String version = userConfigDto.version().get();
        try {
            configRepository.updateUserConfig(userConfig, version);
        } catch (DuplicateUsernameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "username");
        }
        return getUserConfigInternal(userConfig.username());
    }

    @POST(path = "/backend/admin/users/remove", permission = "admin:edit:user")
    String removeUser(@BindRequest UserConfigRequest request) throws Exception {
        try {
            configRepository.deleteUserConfig(request.username().get());
        } catch (CannotDeleteLastUserException e) {
            logger.debug(e.getMessage(), e);
            return "{\"errorCannotDeleteLastUser\":true}";
        }
        return "{}";
    }

    private String getUserConfigInternal(String username) throws Exception {
        UserConfig userConfig = configRepository.getUserConfig(username);
        if (userConfig == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        return mapper.writeValueAsString(ImmutableUserConfigResponse.builder()
                .config(UserConfigDto.create(userConfig))
                .allRoles(getAllRoleNamesInternal())
                .ldapAvailable(!configRepository.getLdapConfig().url().isEmpty())
                .build());
    }

    private List<String> getAllRoleNamesInternal() throws Exception {
        List<String> roleNames = Lists.newArrayList();
        for (RoleConfig roleConfig : configRepository.getRoleConfigs()) {
            roleNames.add(roleConfig.name());
        }
        return roleNames;
    }

    @Value.Immutable
    interface UserConfigRequest {
        Optional<String> username();
    }

    @Value.Immutable
    interface UserConfigResponse {
        UserConfigDto config();
        ImmutableList<String> allRoles();
        boolean ldapAvailable();
    }

    @Value.Immutable
    interface AllRolesResponse {
        ImmutableList<String> allRoles();
        boolean ldapAvailable();
    }

    @Value.Immutable
    abstract static class UserConfigDto {

        abstract String username();
        abstract boolean ldap();
        // only used in request
        @Value.Default
        String newPassword() {
            return "";
        }
        abstract ImmutableList<String> roles();
        abstract Optional<String> version(); // absent for insert operations

        private UserConfig convert(@Nullable UserConfig existingUserConfig)
                throws GeneralSecurityException {
            String passwordHash;
            String newPassword = newPassword();
            if (ldap() || username().equalsIgnoreCase("anonymous")) {
                passwordHash = "";
            } else if (newPassword.isEmpty()) {
                passwordHash = checkNotNull(existingUserConfig).passwordHash();
            } else {
                passwordHash = PasswordHash.createHash(newPassword);
            }
            return ImmutableUserConfig.builder()
                    .username(username())
                    .ldap(ldap())
                    .passwordHash(passwordHash)
                    .roles(roles())
                    .build();
        }

        private static UserConfigDto create(UserConfig config) {
            return ImmutableUserConfigDto.builder()
                    .username(config.username())
                    .ldap(config.ldap())
                    .roles(config.roles())
                    .version(config.version())
                    .build();
        }
    }
}
