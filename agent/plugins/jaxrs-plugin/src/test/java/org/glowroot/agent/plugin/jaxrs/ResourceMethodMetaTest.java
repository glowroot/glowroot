/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.plugin.jaxrs;

import java.util.Collections;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import org.glowroot.agent.plugin.api.MethodInfo;
import org.glowroot.agent.plugin.api.checker.Nullable;

import static org.assertj.core.api.Assertions.assertThat;

public class ResourceMethodMetaTest {

    @Test
    public void should() {
        assertThat(ResourceMethodMeta.combine(null, null)).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("", null)).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine(null, "")).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("", "")).isEqualTo("/");
        assertThat(ResourceMethodMeta.combine("/abc", "/xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("/abc", "xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "/xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "xyz")).isEqualTo("/abc/xyz");
        assertThat(ResourceMethodMeta.combine("abc", null)).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine(null, "xyz")).isEqualTo("/xyz");
        assertThat(ResourceMethodMeta.combine("abc", "")).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine("", "xyz")).isEqualTo("/xyz");
        assertThat(ResourceMethodMeta.combine("/abc", "")).isEqualTo("/abc");
        assertThat(ResourceMethodMeta.combine("", "/xyz")).isEqualTo("/xyz");
    }

    @Test
    public void shouldReadClassPathFromSuperclassLikeSpringCglibProxy() {
        ResourceMethodMeta meta = new ResourceMethodMeta(methodInfo(OrdersProxy.class, "getOrder",
                long.class));
        assertThat(meta.getPath()).isEqualTo("/orders/*");
        assertThat(meta.hasClassPathAnnotation()).isTrue();
    }

    private static MethodInfo methodInfo(final Class<?> declaringClass, final String name,
            final Class<?>... parameterTypes) {
        return new MethodInfo() {
            @Override
            public String getName() {
                return name;
            }
            @Override
            public Class<?> getReturnType() {
                return Response.class;
            }
            @Override
            public List<Class<?>> getParameterTypes() {
                return Collections.<Class<?>>singletonList(parameterTypes[0]);
            }
            @Override
            public String getDeclaringClassName() {
                return declaringClass.getName();
            }
            @Override
            public @Nullable ClassLoader getLoader() {
                return declaringClass.getClassLoader();
            }
        };
    }

    @Path("/orders")
    public static class OrdersResource {
        @GET
        @Path("/{id}")
        public Response getOrder(@PathParam("id") long id) {
            return Response.ok().build();
        }
    }

    // mimics OrderController$$EnhancerBySpringCGLIB$$… (no annotations on the subclass)
    public static class OrdersProxy extends OrdersResource {}
}
