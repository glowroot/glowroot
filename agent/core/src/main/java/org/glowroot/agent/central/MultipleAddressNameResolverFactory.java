/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.central;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Listener;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.grpc.internal.DnsNameResolverProvider;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.central.CentralConnection.CollectorTarget;

import static com.google.common.base.Preconditions.checkNotNull;

// this connection mechanism may be deprecated in the future in favor resolving a single address to
// multiple collectors via DNS
class MultipleAddressNameResolverFactory extends NameResolver.Factory {

    private final List<CollectorTarget> targets;
    private final String authority;

    MultipleAddressNameResolverFactory(List<CollectorTarget> targets, String authority) {
        this.targets = targets;
        this.authority = authority;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new MultipleAddressNameResolver(targets, authority, args);
    }

    @Override
    public String getDefaultScheme() {
        return "dummy-scheme";
    }

    static class MultipleAddressNameResolver extends NameResolver {

        private final NameResolverProvider nameResolverProvider = new DnsNameResolverProvider();

        private final List<CollectorTarget> targets;
        private final String authority;
        private final NameResolver.Args args;

        private volatile @Nullable Listener listener;

        private MultipleAddressNameResolver(List<CollectorTarget> targets, String authority,
                NameResolver.Args args) {
            this.targets = targets;
            this.authority = authority;
            this.args = args;
        }

        @Override
        public String getServiceAuthority() {
            return authority;
        }

        @Override
        public void start(Listener listener) {
            this.listener = listener;
            resolve();
        }

        @Override
        public void refresh() {
            resolve();
        }

        public void resolve() {
            List<NameResolver> nameResolvers = Lists.newArrayList();
            for (CollectorTarget target : targets) {
                URI collectorURI;
                try {
                    collectorURI = new URI(nameResolverProvider.getDefaultScheme(), "",
                            "/" + target.host() + ":" + target.port(), null);
                } catch (URISyntaxException e) {
                    throw new IllegalStateException(e);
                }
                // should not return null since using the name resolver provider's default scheme
                nameResolvers.add(
                        checkNotNull(nameResolverProvider.newNameResolver(collectorURI, args)));
            }
            AggregatingListener aggregatingListener =
                    new AggregatingListener(checkNotNull(listener), nameResolvers);
            for (NameResolver nameResolver : nameResolvers) {
                nameResolver.start(aggregatingListener);
            }
        }

        @Override
        public void shutdown() {}
    }

    private static class AggregatingListener implements Listener {

        private final Listener listener;
        private final List<NameResolver> nameResolvers;

        private final Object lock = new Object();

        @GuardedBy("lock")
        private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
        @GuardedBy("lock")
        private @MonotonicNonNull Attributes attributes;
        @GuardedBy("lock")
        private int onAddressesCount;
        @GuardedBy("lock")
        private boolean closed;

        public AggregatingListener(Listener listener, List<NameResolver> nameResolvers) {
            this.listener = listener;
            this.nameResolvers = nameResolvers;
        }

        @Override
        public void onAddresses(List<EquivalentAddressGroup> servers, Attributes attributes) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                this.servers.addAll(servers);
                if (this.attributes == null) {
                    this.attributes = attributes;
                } else if (!attributes.equals(this.attributes)) {
                    throw new IllegalStateException("New attributes \"" + attributes
                            + "\" are not the same as existing attributes: " + this.attributes);
                }
                if (++onAddressesCount == nameResolvers.size()) {
                    Collections.shuffle(this.servers);
                    listener.onAddresses(this.servers, attributes);
                    close();
                }
            }
        }

        @Override
        public void onError(Status error) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                // send along first error and close
                listener.onError(error);
                close();
            }
        }

        @GuardedBy("lock")
        private void close() {
            for (NameResolver nameResolver : nameResolvers) {
                nameResolver.shutdown();
            }
            closed = true;
        }
    }
}
