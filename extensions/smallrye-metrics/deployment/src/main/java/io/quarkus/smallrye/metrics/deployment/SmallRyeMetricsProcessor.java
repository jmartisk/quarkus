package io.quarkus.smallrye.metrics.deployment;

import org.jboss.logging.Logger;

import io.quarkus.deployment.Capability;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class SmallRyeMetricsProcessor {

    static final Logger LOGGER = Logger.getLogger("io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsProcessor");

    // TODO: what do we do with this?
    //    @BuildStep
    //    MetricsCapabilityBuildItem metricsCapabilityBuildItem(SmallRyeMetricsConfig config) {
    //        if (config.extensionsEnabled) {
    //            return new MetricsCapabilityBuildItem(x -> MetricsFactory.MP_METRICS.equals(x),
    //                    config.path);
    //        }
    //        return null;
    //    }

    @BuildStep
    public CapabilityBuildItem capability() {
        return new CapabilityBuildItem(Capability.METRICS);
    }

    @BuildStep
    public FeatureBuildItem feature() {
        return new FeatureBuildItem(Feature.SMALLRYE_METRICS);
    }

}
