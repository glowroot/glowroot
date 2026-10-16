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
package org.glowroot.central.storage.postgres;

import com.zaxxer.hikari.HikariDataSource;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.ConfigRepository.DuplicateRoleNameException;
import org.glowroot.common2.repo.ConfigRepository.DuplicateUsernameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres-store")
public class PostgresUserRoleIT {

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private PostgresUserDao userDao;
    private PostgresRoleDao roleDao;

    @BeforeEach
    public void before() throws Exception {
        dataSource = PostgresDataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5);
        PostgresSchemaUpgrade.upgrade(dataSource);
        userDao = new PostgresUserDao(dataSource);
        roleDao = new PostgresRoleDao(dataSource);
    }

    @AfterEach
    public void after() {
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }

    @Test
    public void seedsAnonymousAdministrator() throws Exception {
        UserConfig anonymous = userDao.read("anonymous").toCompletableFuture().join();
        assertThat(anonymous).isNotNull();
        assertThat(anonymous.roles()).contains("Administrator");

        RoleConfig admin = roleDao.read("Administrator").toCompletableFuture().join();
        assertThat(admin).isNotNull();
        assertThat(admin.permissions()).contains("admin");
    }

    @Test
    public void insertAndReadUser() throws Exception {
        userDao.insert(ImmutableUserConfig.builder()
                .username("abc")
                .passwordHash("xyz")
                .addRoles("arole", "brole")
                .build()).toCompletableFuture().join();

        UserConfig userConfig = userDao.read("abc").toCompletableFuture().join();
        assertThat(userConfig).isNotNull();
        assertThat(userConfig.username()).isEqualTo("abc");
        assertThat(userConfig.passwordHash()).isEqualTo("xyz");
        assertThat(userConfig.roles()).containsExactly("arole", "brole");
    }

    @Test
    public void duplicateUsernameFails() {
        ImmutableUserConfig user = ImmutableUserConfig.builder()
                .username("dup")
                .passwordHash("h")
                .addRoles("r")
                .build();
        userDao.insertIfNotExists(user).toCompletableFuture().join();
        assertThatThrownBy(() -> userDao.insertIfNotExists(user).toCompletableFuture().join())
                .hasCauseInstanceOf(DuplicateUsernameException.class);
    }

    @Test
    public void insertAndReadRole() throws Exception {
        roleDao.insert(ImmutableRoleConfig.builder()
                .central(true)
                .name("reader")
                .addPermissions("agent:*:transaction")
                .build()).toCompletableFuture().join();

        RoleConfig role = roleDao.read("reader").toCompletableFuture().join();
        assertThat(role).isNotNull();
        assertThat(role.name()).isEqualTo("reader");
        assertThat(role.permissions()).containsExactly("agent:*:transaction");
    }

    @Test
    public void duplicateRoleFails() {
        ImmutableRoleConfig role = ImmutableRoleConfig.builder()
                .central(true)
                .name("duprole")
                .addPermissions("admin")
                .build();
        roleDao.insertIfNotExists(role).toCompletableFuture().join();
        assertThatThrownBy(() -> roleDao.insertIfNotExists(role).toCompletableFuture().join())
                .hasCauseInstanceOf(DuplicateRoleNameException.class);
    }
}
