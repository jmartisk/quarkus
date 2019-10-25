package io.quarkus.smallrye.metrics.deployment.spi;

import java.util.concurrent.Callable;

import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Tag;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * Build item that is picked up by the SmallRye Metrics extension to register metrics required by other extensions.
 */
public final class AdditionalMetricBuildItem extends MultiBuildItem {

    private final Metadata metadata;
    private final Tag[] tags;
    private final Callable<Number> callable;

    /**
     * Create a metric build item from the specified metadata and tags.
     * Such metric will be picked up by the Metrics extension and registered in the VENDOR registry.
     * This constructor is applicable to all metric types except gauges.
     *
     * @param metadata The metadata that should be applied to the registered metric
     * @param tags The tags that will be applied to this metric
     */
    public AdditionalMetricBuildItem(Metadata metadata, Tag... tags) {
        this.metadata = metadata;
        this.tags = tags;
        this.callable = null;
    }

    /**
     * Create a metric build item from the specified metadata, tags, and a callable.
     * Such metric will be picked up by the Metrics extension and registered in the VENDOR registry.
     *
     * @param metadata The metadata that should be applied to the registered metric
     * @param callable The callable that implements a Gauge. Can be non-null only for Gauges, must be null for other metric types.
     *                 It must reference a Callable instance that is available at runtime.
     * @param tags The tags that will be applied to this metric
     *
     */
    public AdditionalMetricBuildItem(Metadata metadata, Callable<Number> callable, Tag... tags) {
        if (callable != null && metadata.getTypeRaw() != MetricType.GAUGE) {
            throw new IllegalArgumentException("Callables are for MetricType=GAUGE only");
        }
        if (callable == null && metadata.getTypeRaw() == MetricType.GAUGE) {
            throw new IllegalArgumentException("Gauges require a non-null Callable reference");
        }
        this.metadata = metadata;
        this.tags = tags;
        this.callable = callable;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public Tag[] getTags() {
        return tags;
    }

    public Callable<Number> getCallable() {
        return callable;
    }
}
