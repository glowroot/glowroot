/*
 * Copyright 2016 the original author or authors.
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

import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import com.google.common.collect.Sets;
import org.immutables.value.Value;

import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.repo.util.Encryption;

import static com.google.common.base.Preconditions.checkNotNull;

class LdapAuthentication {

    static Set<String> getGlowrootRoles(Set<String> ldapGroupDns, LdapConfig ldapConfig)
            throws NamingException {
        Set<String> glowrootRoles = Sets.newHashSet();
        for (String ldapGroupDn : ldapGroupDns) {
            List<String> roles = ldapConfig.roleMappings().get(ldapGroupDn);
            if (roles != null) {
                glowrootRoles.addAll(roles);
            }
        }
        return glowrootRoles;
    }

    static Set<String> authenticateAndGetLdapGroupDns(String username, String password,
            LdapConfig ldapConfig, SecretKey secretKey) throws Exception {
        String systemUsername = ldapConfig.username();
        String systemPassword = ldapConfig.password();
        if (!systemPassword.isEmpty()) {
            systemPassword = Encryption.decrypt(systemPassword, secretKey);
        }
        LdapContext ldapContext;
        try {
            ldapContext = createLdapContext(systemUsername, systemPassword, ldapConfig);
        } catch (NamingException e) {
            throw new AuthenticationException("System LDAP authentication failed", e);
        }
        String userDn;
        try {
            userDn = getUserDn(ldapContext, username, ldapConfig);
        } catch (NamingException e) {
            throw new AuthenticationException(e);
        }
        if (userDn == null) {
            throw new AuthenticationException("User not found: " + username);
        }
        try {
            createLdapContext(userDn, password, ldapConfig);
            return getGroupDnsForUserDn(ldapContext, userDn, ldapConfig);
        } catch (NamingException e) {
            throw new AuthenticationException(e);
        }
    }

    private static LdapContext createLdapContext(String username, String password,
            LdapConfig ldapConfig) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<String, Object>();
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, username);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapConfig.url());
        return new InitialLdapContext(env, null);
    }

    private static @Nullable String getUserDn(LdapContext ldapContext, String username,
            LdapConfig ldapConfig) throws NamingException {
        SearchControls searchCtls = new SearchControls();
        searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        NamingEnumeration<?> namingEnum = ldapContext.search(ldapConfig.userBaseDn(),
                ldapConfig.userSearchFilter(), new String[] {username}, searchCtls);
        if (!namingEnum.hasMore()) {
            return null;
        }
        SearchResult result = (SearchResult) checkNotNull(namingEnum.next());
        String userDn = result.getNameInNamespace();
        if (namingEnum.hasMore()) {
            throw new IllegalStateException("More than matching user: " + username);
        }
        namingEnum.close();
        return userDn;
    }

    private static Set<String> getGroupDnsForUserDn(LdapContext ldapContext, String userDn,
            LdapConfig ldapConfig) throws NamingException {
        SearchControls searchCtls = new SearchControls();
        searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        NamingEnumeration<?> namingEnum = ldapContext.search(ldapConfig.groupBaseDn(),
                ldapConfig.groupSearchFilter(), new String[] {userDn}, searchCtls);
        Set<String> ldapGroups = Sets.newHashSet();
        while (namingEnum.hasMore()) {
            SearchResult result = (SearchResult) checkNotNull(namingEnum.next());
            ldapGroups.add(result.getNameInNamespace());
        }
        namingEnum.close();
        return ldapGroups;
    }

    @Value.Immutable
    interface AuthenticationResult {
        String userDn();
        Set<String> ldapGroupDns();
    }

    @SuppressWarnings("serial")
    static class AuthenticationException extends Exception {

        AuthenticationException(String message) {
            super(message);
        }

        AuthenticationException(Throwable cause) {
            super(cause);
        }

        AuthenticationException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
