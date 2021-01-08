package io.quarkus.smallrye.metrics.runtime;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.graalvm.nativeimage.ImageInfo;

import io.micrometer.core.instrument.Metrics;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.metrics.MetricRegistries;
import io.smallrye.metrics.base.LegacyBaseMetrics;
import io.smallrye.metrics.elementdesc.BeanInfo;
import io.smallrye.metrics.elementdesc.MemberInfo;
import io.smallrye.metrics.legacyapi.interceptors.MetricResolver;
import io.smallrye.metrics.setup.MetricsMetadata;

/**
 * Recorder for things related to legacy MP Metrics annotations.
 */
@Recorder
public class LegacyMetricsRecorder {

    public void registerMetrics(BeanInfo beanInfo, MemberInfo memberInfo) {
        MetricRegistry registry = MetricRegistries.get(MetricRegistry.Type.APPLICATION);
        MetricsMetadata.registerMetrics(registry,
                new MetricResolver(),
                beanInfo,
                memberInfo);
    }

    public void registerLegacyBaseMetrics() {
        boolean nativeMode = ImageInfo.inImageCode();
        new LegacyBaseMetrics(nativeMode).bindTo(Metrics.globalRegistry);
    }

    public void createLegacyRegistries(BeanContainer container) {
        MetricRegistries.get(MetricRegistry.Type.APPLICATION);
        MetricRegistries.get(MetricRegistry.Type.BASE);
        MetricRegistries.get(MetricRegistry.Type.VENDOR);

        //HACK: registration is done via statics, but cleanup is done via pre destroy
        //however if the bean is not used it will not be created, so no cleanup will be done
        //we force bean creation here to make sure the container can restart correctly
        container.instance(MetricRegistries.class).getApplicationRegistry();
    }

    public void dropRegistriesAtShutdown(ShutdownContext shutdownContext) {
        shutdownContext.addShutdownTask(MetricRegistries::dropAll);
    }

}
