package io.quarkus.smallrye.metrics.runtime;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

/**
 * Global configuration for the SmallRye Metrics extension.
 */
@ConfigRoot(name = "smallrye-metrics", phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public final class SmallRyeMetricsConfig {

    /**
     * Expose base metrics as described in MP Metrics 3.0 instead of the JVM metrics provided Micrometer.
     */
    @ConfigItem(name = "mpmetrics30.base.metrics", defaultValue = "false")
    public boolean mpMetrics30BaseMetrics;

}
