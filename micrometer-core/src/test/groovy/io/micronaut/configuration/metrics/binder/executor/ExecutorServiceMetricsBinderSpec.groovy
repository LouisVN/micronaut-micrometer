/*
 * Copyright 2017-2019 original authors
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
package io.micronaut.configuration.metrics.binder.executor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.search.RequiredSearch
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scheduling.TaskExecutors
import io.netty.channel.DefaultEventLoop
import io.netty.channel.EventLoopGroup
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import javax.inject.Named
import javax.inject.Singleton
import java.util.concurrent.ExecutorService

import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_BINDERS
import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_ENABLED

class ExecutorServiceMetricsBinderSpec extends Specification {

    def "test executor service metrics"() {
        when:
        ApplicationContext context = ApplicationContext.run()
        ExecutorService executorService = context.getBean(ExecutorService, Qualifiers.byName(TaskExecutors.IO))

        executorService.submit({ -> } as Runnable)
        executorService.submit({ -> } as Runnable)

        MeterRegistry registry = context.getBean(MeterRegistry)
        RequiredSearch search = registry.get("executor.pool.size")
        search.tags("name", "io")

        Gauge g = search.gauge()

        PollingConditions conditions = new PollingConditions(timeout: 3, delay: 0.1)

        then:"The pool size was expanded to handle the 2 runnables"
        conditions.eventually {
            g.value() > 0
        }

        cleanup:
        context.close()
    }

    @Issue("https://github.com/micronaut-projects/micronaut-micrometer/issues/62")
    void "test event loop group not instrumented"() {

        when:
        ApplicationContext context = ApplicationContext.run()
        EventLoopGroup eventLoopGroup = context.findBean(EventLoopGroup, Qualifiers.byName("test"))
                                                .orElse(null)

        then:
        // for Micronaut 2.0 the value will not be non-null since a bean will be present
        // we are mainly asserting here that a ClassCastException doesn't occur in
        // the previous assignment. See #62
        eventLoopGroup == null || eventLoopGroup instanceof EventLoopGroup

        cleanup:
        context.close()
    }

    @Unroll
    def "test getting the beans #cfg #setting"() {
        when:
        ApplicationContext context = ApplicationContext.run([(cfg): setting])

        then:
        context.findBean(ExecutorServiceMetricsBinder).isPresent() == setting

        cleanup:
        context.close()

        where:
        cfg                                             | setting
        MICRONAUT_METRICS_ENABLED                       | true
        MICRONAUT_METRICS_ENABLED                       | false
        MICRONAUT_METRICS_BINDERS + ".executor.enabled" | true
        MICRONAUT_METRICS_BINDERS + ".executor.enabled" | false
    }

    @Factory
    static class TestEventLoopGroupFactory {
        @Singleton
        @Named("test")
        @Requires(sdk = Requires.Sdk.MICRONAUT, version = "2.0.0")
        EventLoopGroup eventLoopGroup() {
            return new DefaultEventLoop()
        }
    }
}
