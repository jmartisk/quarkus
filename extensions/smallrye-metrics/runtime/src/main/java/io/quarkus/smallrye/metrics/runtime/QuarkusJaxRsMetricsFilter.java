/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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

package io.quarkus.smallrye.metrics.runtime;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

import javax.enterprise.inject.spi.CDI;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;

import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.metrics.MetricRegistries;
import io.vertx.ext.web.RoutingContext;

/**
 * A JAX-RS filter that computes the REST.request metrics from REST traffic over time.
 * This one depends on Vert.x to be able to hook into response even in cases when the request ended with an unmapped exception.
 */
public class QuarkusJaxRsMetricsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        Long start = System.nanoTime();
        final Class<?> resourceClass = resourceInfo.getResourceClass();
        final Method resourceMethod = resourceInfo.getResourceMethod();
        maybeCreateMetrics(resourceClass, resourceMethod);
        /*
         * The reason for using a Vert.x handler instead of ContainerResponseFilter is that
         * RESTEasy does not call the response filter for requests that ended up with an unmapped exception.
         * This way we can capture these responses as well and update the metrics accordingly.
         */
        RoutingContext routingContext = CDI.current().select(CurrentVertxRequest.class).get().getCurrent();
        routingContext.addBodyEndHandler(
                event -> finishRequest(start, resourceClass, resourceMethod, requestContext));
    }

    private void finishRequest(Long start, Class<?> resourceClass, Method resourceMethod,
            ContainerRequestContext requestContext) {
        long value = System.nanoTime() - start;
        boolean success = requestContext.getProperty("smallrye.metrics.jaxrs.successful") != null;
        MetricID metricID = getMetricID(resourceClass, resourceMethod, success);
        MetricRegistry registry = MetricRegistries.get(MetricRegistry.Type.BASE);
        if (success) {
            registry.simpleTimer(metricID).update(Duration.ofNanos(value));
        } else {
            registry.counter(metricID).inc();
        }
    }

    private MetricID getMetricID(Class<?> resourceClass, Method resourceMethod, boolean requestWasSuccessful) {
        Tag classTag = new Tag("class", resourceClass.getName());
        String methodName = resourceMethod.getName();
        String encodedParameterNames = Arrays.stream(resourceMethod.getParameterTypes())
                .map(clazz -> {
                    if (clazz.isArray()) {
                        return clazz.getComponentType().getName() + "[]";
                    } else {
                        return clazz.getName();
                    }
                })
                .collect(Collectors.joining("_"));
        String methodTagValue = encodedParameterNames.isEmpty() ? methodName : methodName + "_" + encodedParameterNames;
        Tag methodTag = new Tag("method", methodTagValue);
        String name = requestWasSuccessful ? "REST.request" : "REST.request.unmappedException.total";
        return new MetricID(name, classTag, methodTag);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // mark the request as successful if it finished without exception or with a mapped exception
        // if it ended with an unmapped exception, this filter is not called by RESTEasy
        requestContext.setProperty("smallrye.metrics.jaxrs.successful", true);
    }

    private void maybeCreateMetrics(Class<?> resourceClass, Method resourceMethod) {
        MetricRegistry registry = MetricRegistries.get(MetricRegistry.Type.BASE);
        MetricID success = getMetricID(resourceClass, resourceMethod, true);
        if (registry.getSimpleTimer(success) == null) {
            Metadata successMetadata = Metadata.builder()
                    .withName(success.getName())
                    .withDescription(
                            "The number of invocations and total response time of this RESTful " +
                                    "resource method since the start of the server.")
                    .withUnit(MetricUnits.NANOSECONDS)
                    .build();
            registry.simpleTimer(successMetadata, success.getTagsAsArray());
        }
        MetricID failure = getMetricID(resourceClass, resourceMethod, false);
        if (registry.getCounter(failure) == null) {
            Metadata failureMetadata = Metadata.builder()
                    .withName(failure.getName())
                    .withDisplayName("Total Unmapped Exceptions count")
                    .withDescription(
                            "The total number of unmapped exceptions that occurred from this RESTful resource " +
                                    "method since the start of the server.")
                    .build();
            registry.counter(failureMetadata, failure.getTagsAsArray());
        }
    }
}
