/*
 * Copyright 2019 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.observation.contextpropagation;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.concurrent.*;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;

class ObservationThreadLocalAccessorTests {

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    ObservationRegistry observationRegistry = ObservationRegistry.create();

    ContextRegistry registry = new ContextRegistry();

    @BeforeEach
    void setup() {
        observationRegistry.observationConfig().observationHandler(new TracingHandler());
        registry.registerThreadLocalAccessor(new ObservationThreadLocalAccessor());
        ContextRegistry.getInstance();
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(observationRegistry);
    }

    @AfterEach
    void clean() {
        executorService.shutdown();
    }

    @Test
    void capturedThreadLocalValuesShouldBeCapturedRestoredAndCleared()
            throws InterruptedException, ExecutionException, TimeoutException {

        // given
        Observation parent = Observation.start("parent", observationRegistry);
        Observation child = Observation.createNotStarted("foo", observationRegistry).parentObservation(parent).start();
        thenCurrentObservationIsNull();

        // when context captured
        ContextSnapshot container;
        try (Observation.Scope scope = child.openScope()) {
            thenCurrentObservationHasParent(parent, child);
            thenCurrentScopeHasParent(null);
            container = ContextSnapshot.captureAllUsing(key -> true, registry);
        }

        thenCurrentObservationIsNull();

        // when first scope created
        try (ContextSnapshot.Scope scope = container.setThreadLocals()) {
            thenCurrentObservationHasParent(parent, child);
            Scope inScope = thenCurrentScopeHasParent(null);

            // when second, nested scope created
            try (ContextSnapshot.Scope scope2 = container.setThreadLocals()) {
                thenCurrentObservationHasParent(parent, child);
                Scope inSecondScope = thenCurrentScopeHasParent(inScope);

                // when context gets propagated to a new thread
                ContextExecutorService.wrap(executorService, () -> container).submit(() -> {
                    thenCurrentObservationHasParent(parent, child);
                    thenCurrentScopeHasParent(null);
                }).get(5, TimeUnit.SECONDS);

                then(scopeParent(inSecondScope)).isSameAs(inScope);
            }
            then(scopeParent(inScope)).isSameAs(null);
        }

        thenCurrentObservationIsNull();

        child.stop();
        parent.stop();
    }

    @Test
    void exceptionShouldBeThrownWhenObservationRegistryNotSet() {
        ObservationThreadLocalAccessor accessor = new ObservationThreadLocalAccessor();

        thenThrownBy(accessor::getValue).hasMessageContaining("You must override the default ObservationRegistry");
        thenThrownBy(accessor::reset).hasMessageContaining("You must override the default ObservationRegistry");
        thenThrownBy(() -> accessor.restore(Observation.NOOP))
                .hasMessageContaining("You must override the default ObservationRegistry");
    }

    private void thenCurrentObservationHasParent(Observation parent, Observation observation) {
        then(observationRegistry.getCurrentObservation()).isSameAs(observation);
        then(observationRegistry.getCurrentObservation().getContextView().getParentObservation()).isSameAs(parent);
    }

    private Scope thenCurrentScopeHasParent(Scope first) {
        Scope inScope = TracingHandler.value.get();
        then(scopeParent(inScope)).isSameAs(first);
        return inScope;
    }

    private void thenCurrentObservationIsNull() {
        then(observationRegistry.getCurrentObservation()).isNull();
        then(TracingHandler.value.get()).isSameAs(null);
    }

    private Scope scopeParent(Scope scope) {
        return scope.parent();
    }

    static class TracingHandler implements ObservationHandler<Observation.Context> {

        static final ThreadLocal<Scope> value = new ThreadLocal<>();

        @Override
        public void onStart(Observation.Context context) {
            System.out.println("on start [" + context + "]");
        }

        @Override
        public void onScopeOpened(Observation.Context context) {
            Scope currentScope = value.get();
            Scope newScope = new Scope(currentScope);
            context.put(Scope.class, newScope);
            System.out.println("\ton open scope [" + context + "]");
        }

        @Override
        public void onScopeClosed(Observation.Context context) {
            Scope scope = context.get(Scope.class);
            scope.close();
            System.out.println("\ton scope remove [" + context + "]");
        }

        @Override
        public void onStop(Observation.Context context) {
            context.put("state", "stopped");
            System.out.println("on stop [" + context.getName() + "]");
        }

        @Override
        public void onScopeReset() {
            value.remove();
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

    }

    static class Scope implements Closeable {

        private final Scope previous;

        Scope(Scope previous) {
            this.previous = previous;
            TracingHandler.value.set(this);
        }

        @Override
        public void close() {
            TracingHandler.value.set(previous);
        }

        Scope parent() {
            return this.previous;
        }

    }

}
