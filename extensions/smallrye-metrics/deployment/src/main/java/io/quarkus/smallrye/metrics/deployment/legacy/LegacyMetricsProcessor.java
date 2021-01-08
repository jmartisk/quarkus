package io.quarkus.smallrye.metrics.deployment.legacy;

import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;
import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.CONCURRENT_GAUGE_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.COUNTER_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.GAUGE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.GAUGE_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.HISTOGRAM_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.METER_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.METRIC;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.METRICS_ANNOTATIONS;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.METRICS_BINDING;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.SIMPLE_TIMER_INTERFACE;
import static io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames.TIMER_INTERFACE;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.Dependent;

import org.eclipse.microprofile.metrics.MetricType;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.AutoInjectAnnotationBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.CustomScopeAnnotationsBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import io.quarkus.smallrye.metrics.deployment.SmallRyeMetricsDotNames;
import io.quarkus.smallrye.metrics.deployment.jandex.JandexBeanInfoAdapter;
import io.quarkus.smallrye.metrics.deployment.jandex.JandexMemberInfoAdapter;
import io.quarkus.smallrye.metrics.runtime.LegacyMetricsRecorder;
import io.quarkus.smallrye.metrics.runtime.SmallRyeMetricsConfig;
import io.smallrye.metrics.MetricProducer;
import io.smallrye.metrics.MetricRegistries;
import io.smallrye.metrics.MetricsRequestHandler;
import io.smallrye.metrics.elementdesc.BeanInfo;
import io.smallrye.metrics.legacyapi.interceptors.ConcurrentGaugeInterceptor;
import io.smallrye.metrics.legacyapi.interceptors.CountedInterceptor;
import io.smallrye.metrics.legacyapi.interceptors.MeteredInterceptor;
import io.smallrye.metrics.legacyapi.interceptors.MetricNameFactory;
import io.smallrye.metrics.legacyapi.interceptors.MetricsBinding;
import io.smallrye.metrics.legacyapi.interceptors.SimplyTimedInterceptor;
import io.smallrye.metrics.legacyapi.interceptors.TimedInterceptor;

public class LegacyMetricsProcessor {

    static final Logger LOGGER = Logger
            .getLogger("io.quarkus.smallrye.metrics.deployment.annotations.LegacyMetricsProcessor");

    @BuildStep
    void beans(BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(MetricProducer.class,
                MetricNameFactory.class,
                MetricRegistries.class,
                MetricsRequestHandler.class,
                CountedInterceptor.class,
                ConcurrentGaugeInterceptor.class,
                MeteredInterceptor.class,
                TimedInterceptor.class,
                SimplyTimedInterceptor.class));
        unremovableBeans.produce(new UnremovableBeanBuildItem(
                new UnremovableBeanBuildItem.BeanClassNameExclusion(MetricsRequestHandler.class.getName())));
    }

    @BuildStep
    @Record(STATIC_INIT)
    public void build(BeanContainerBuildItem beanContainerBuildItem,
            LegacyMetricsRecorder metrics,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        for (DotName metricsAnnotation : METRICS_ANNOTATIONS) {
            reflectiveClasses.produce(new ReflectiveClassBuildItem(false, false, metricsAnnotation.toString()));
        }

        metrics.createLegacyRegistries(beanContainerBuildItem.getValue());
    }

    @BuildStep
    @Record(STATIC_INIT)
    void registerMetricsFromAnnotatedMethods(LegacyMetricsRecorder metrics,
            BeanArchiveIndexBuildItem beanArchiveIndex) {
        IndexView index = beanArchiveIndex.getIndex();
        JandexBeanInfoAdapter beanInfoAdapter = new JandexBeanInfoAdapter(index);
        JandexMemberInfoAdapter memberInfoAdapter = new JandexMemberInfoAdapter(index);

        Set<MethodInfo> collectedMetricsMethods = new HashSet<>();
        Map<DotName, ClassInfo> collectedMetricsClasses = new HashMap<>();

        // find stereotypes that contain metric annotations so we can include them in the search
        Set<DotName> metricAndStereotypeAnnotations = new HashSet<>();
        metricAndStereotypeAnnotations.addAll(METRICS_ANNOTATIONS);
        for (ClassInfo candidate : beanArchiveIndex.getIndex().getKnownClasses()) {
            if (candidate.classAnnotation(DotNames.STEREOTYPE) != null &&
                    candidate.classAnnotations().stream()
                            .anyMatch(SmallRyeMetricsDotNames::isMetricAnnotation)) {
                metricAndStereotypeAnnotations.add(candidate.name());
            }
        }

        for (DotName metricAnnotation : metricAndStereotypeAnnotations) {
            Collection<AnnotationInstance> metricAnnotationInstances = index.getAnnotations(metricAnnotation);
            for (AnnotationInstance metricAnnotationInstance : metricAnnotationInstances) {
                AnnotationTarget metricAnnotationTarget = metricAnnotationInstance.target();
                switch (metricAnnotationTarget.kind()) {
                    case METHOD:
                        MethodInfo method = metricAnnotationTarget.asMethod();
                        if (!method.declaringClass().name().toString().startsWith("io.smallrye.metrics")) {
                            if (!Modifier.isPrivate(method.flags())) {
                                collectedMetricsMethods.add(method);
                            } else {
                                LOGGER.warn("Private method is annotated with a metric: " + method +
                                        " in class " + method.declaringClass().name() + ". Metrics " +
                                        "are not collected for private methods. To enable metrics for this method, make " +
                                        "it at least package-private.");
                            }
                        }
                        break;
                    case CLASS:
                        ClassInfo clazz = metricAnnotationTarget.asClass();
                        if (!clazz.name().toString().startsWith("io.smallrye.metrics")) {
                            collectMetricsClassAndSubClasses(index, collectedMetricsClasses, clazz);
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        for (ClassInfo clazz : collectedMetricsClasses.values()) {
            BeanInfo beanInfo = beanInfoAdapter.convert(clazz);
            ClassInfo superclass = clazz;
            Set<String> alreadyRegisteredNames = new HashSet<>();
            // register metrics for all inherited methods as well
            while (superclass != null && superclass.superName() != null) {
                for (MethodInfo method : superclass.methods()) {
                    if (!Modifier.isPrivate(method.flags()) && !alreadyRegisteredNames.contains(method.name())) {
                        metrics.registerMetrics(beanInfo, memberInfoAdapter.convert(method));
                        alreadyRegisteredNames.add(method.name());
                    }
                }
                // find inherited default methods which are not overridden by the original bean
                for (Type interfaceType : superclass.interfaceTypes()) {
                    ClassInfo ifaceInfo = beanArchiveIndex.getIndex().getClassByName(interfaceType.name());
                    if (ifaceInfo != null) {
                        findNonOverriddenDefaultMethods(ifaceInfo, alreadyRegisteredNames, metrics, beanArchiveIndex,
                                memberInfoAdapter,
                                beanInfo);
                    }
                }
                superclass = index.getClassByName(superclass.superName());
            }
        }

        for (MethodInfo method : collectedMetricsMethods) {
            ClassInfo declaringClazz = method.declaringClass();
            if (!collectedMetricsClasses.containsKey(declaringClazz.name())) {
                BeanInfo beanInfo = beanInfoAdapter.convert(declaringClazz);
                metrics.registerMetrics(beanInfo, memberInfoAdapter.convert(method));
            }
        }
    }

    private void findNonOverriddenDefaultMethods(ClassInfo interfaceInfo, Set<String> alreadyRegisteredNames,
            LegacyMetricsRecorder recorder,
            BeanArchiveIndexBuildItem beanArchiveIndex, JandexMemberInfoAdapter memberInfoAdapter, BeanInfo beanInfo) {
        // Check for default methods which are NOT overridden by the bean that we are registering metrics for
        // or any of its superclasses. Register a metric for each of them.
        for (MethodInfo method : interfaceInfo.methods()) {
            if (!Modifier.isAbstract(method.flags())) { // only take default methods
                if (!alreadyRegisteredNames.contains(method.name())) {
                    recorder.registerMetrics(beanInfo, memberInfoAdapter.convert(method));
                    alreadyRegisteredNames.add(method.name());
                }
            }
        }
        // recursively repeat the same for interfaces which this interface extends
        for (Type extendedInterface : interfaceInfo.interfaceTypes()) {
            ClassInfo extendedInterfaceInfo = beanArchiveIndex.getIndex().getClassByName(extendedInterface.name());
            if (extendedInterfaceInfo != null) {
                findNonOverriddenDefaultMethods(extendedInterfaceInfo, alreadyRegisteredNames, recorder, beanArchiveIndex,
                        memberInfoAdapter,
                        beanInfo);
            }
        }
    }

    @BuildStep
    AnnotationsTransformerBuildItem transformBeanScope(BeanArchiveIndexBuildItem index,
            CustomScopeAnnotationsBuildItem scopes) {
        return new AnnotationsTransformerBuildItem(new AnnotationsTransformer() {

            @Override
            public int getPriority() {
                // this specifically should run after the JAX-RS AnnotationTransformers
                return BuildExtension.DEFAULT_PRIORITY - 100;
            }

            @Override
            public boolean appliesTo(Kind kind) {
                return kind == Kind.CLASS;
            }

            @Override
            public void transform(TransformationContext ctx) {
                if (scopes.isScopeIn(ctx.getAnnotations())) {
                    return;
                }
                ClassInfo clazz = ctx.getTarget().asClass();
                if (!isJaxRsEndpoint(clazz) && !isJaxRsProvider(clazz)) {
                    while (clazz != null && clazz.superName() != null) {
                        Map<DotName, List<AnnotationInstance>> annotations = clazz.annotations();
                        if (annotations.containsKey(GAUGE)
                                || annotations.containsKey(SmallRyeMetricsDotNames.CONCURRENT_GAUGE)
                                || annotations.containsKey(SmallRyeMetricsDotNames.COUNTED)
                                || annotations.containsKey(SmallRyeMetricsDotNames.METERED)
                                || annotations.containsKey(SmallRyeMetricsDotNames.SIMPLY_TIMED)
                                || annotations.containsKey(SmallRyeMetricsDotNames.TIMED)
                                || annotations.containsKey(SmallRyeMetricsDotNames.METRIC)) {
                            LOGGER.debugf(
                                    "Found metrics business methods on a class %s with no scope defined - adding @Dependent",
                                    ctx.getTarget());
                            ctx.transform().add(Dependent.class).done();
                            break;
                        }
                        clazz = index.getIndex().getClassByName(clazz.superName());
                    }
                }
            }
        });
    }

    @BuildStep
    AnnotationsTransformerBuildItem annotationTransformers() {
        // attach @MetricsBinding to each class that contains any metric annotations
        return new AnnotationsTransformerBuildItem(new AnnotationsTransformer() {

            @Override
            public boolean appliesTo(Kind kind) {
                return kind == Kind.CLASS;
            }

            @Override
            public void transform(TransformationContext context) {
                // skip classes in package io.smallrye.metrics.interceptors
                ClassInfo clazz = context.getTarget().asClass();
                if (clazz.name().toString()
                        .startsWith("io.smallrye.metrics.interceptors")) {
                    return;
                }
                if (clazz.annotations().containsKey(GAUGE)) {
                    BuiltinScope beanScope = BuiltinScope.from(clazz);
                    if (!isJaxRsEndpoint(clazz) && beanScope != null &&
                            !beanScope.equals(BuiltinScope.APPLICATION) &&
                            !beanScope.equals(BuiltinScope.SINGLETON)) {
                        LOGGER.warnf("Bean %s declares a org.eclipse.microprofile.metrics.annotation.Gauge " +
                                "but is of a scope that typically " +
                                "creates multiple instances. Gauges are forbidden on beans " +
                                "that create multiple instances, this will cause errors " +
                                "when constructing them. Please use annotated gauges only in beans with " +
                                "@ApplicationScoped or @Singleton scopes, or in JAX-RS endpoints.",
                                clazz.name().toString());
                    }
                    context.transform().add(MetricsBinding.class).done();
                }
            }

        });
    }

    @BuildStep
    void reflectiveClasses(BeanArchiveIndexBuildItem beanArchiveIndex,
            BuildProducer<ReflectiveMethodBuildItem> reflectiveMethods,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        /*
         * Methods with a @Gauge annotation need to be registered for reflection because
         * gauges are registered at runtime and the registering interceptor must be able to see
         * the annotation.
         */
        for (AnnotationInstance annotation : beanArchiveIndex.getIndex().getAnnotations(GAUGE)) {
            if (annotation.target().kind().equals(Kind.METHOD)) {
                reflectiveMethods.produce(new ReflectiveMethodBuildItem(annotation.target().asMethod()));
            }
        }
        for (DotName metricsAnnotation : METRICS_ANNOTATIONS) {
            reflectiveClasses.produce(new ReflectiveClassBuildItem(false, false, metricsAnnotation.toString()));
        }

        reflectiveClasses.produce(new ReflectiveClassBuildItem(false, false, METRICS_BINDING.toString()));
    }

    @BuildStep
    AutoInjectAnnotationBuildItem autoInjectMetric() {
        return new AutoInjectAnnotationBuildItem(SmallRyeMetricsDotNames.METRIC);
    }

    @BuildStep
    public void warnAboutMetricsFromProducers(ValidationPhaseBuildItem validationPhase,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            BuildProducer<ValidationPhaseBuildItem.ValidationErrorBuildItem> unused) {
        for (io.quarkus.arc.processor.BeanInfo bean : validationPhase.getContext().beans().producers()) {
            ClassInfo implClazz = bean.getImplClazz();
            if (implClazz == null) {
                continue;
            }
            MetricType metricType = getMetricType(implClazz);
            if (metricType != null) {
                AnnotationTarget target = bean.getTarget().get();
                AnnotationInstance metricAnnotation = null;
                if (bean.isProducerField()) {
                    FieldInfo field = target.asField();
                    metricAnnotation = field.annotation(METRIC);
                }
                if (bean.isProducerMethod()) {
                    MethodInfo method = target.asMethod();
                    metricAnnotation = method.annotation(METRIC);
                }
                if (metricAnnotation != null) {
                    LOGGER.warn(
                            "Metrics created from CDI producers are no longer supported. There will be no metric automatically registered "
                                    +
                                    "for producer " + target);
                }
            }
        }
    }

    /**
     * When shutting down, drop all legacy metric registries. Specifically in dev mode,
     * this is to ensure all metrics start from zero after a reload, and that extensions
     * don't have to deregister their own metrics manually.
     */
    @BuildStep
    @Record(RUNTIME_INIT)
    void dropRegistriesAtShutdown(LegacyMetricsRecorder recorder,
            ShutdownContextBuildItem shutdown) {
        recorder.dropRegistriesAtShutdown(shutdown);
    }

    @BuildStep
    @Record(RUNTIME_INIT)
    void registerBaseMetrics(LegacyMetricsRecorder metrics,
            ShutdownContextBuildItem shutdown,
            SmallRyeMetricsConfig config) {
        if (config.mpMetrics30BaseMetrics) {
            // TODO: should we disable the default Micrometer binders in this case?
            metrics.registerLegacyBaseMetrics();
        }
    }

    /**
     * Obtains the MetricType from a bean that is a producer method or field,
     * or null if no MetricType can be detected.
     */
    private MetricType getMetricType(ClassInfo clazz) {
        DotName name = clazz.name();
        if (name.equals(GAUGE_INTERFACE)) {
            return MetricType.GAUGE;
        }
        if (name.equals(COUNTER_INTERFACE)) {
            return MetricType.COUNTER;
        }
        if (name.equals(CONCURRENT_GAUGE_INTERFACE)) {
            return MetricType.CONCURRENT_GAUGE;
        }
        if (name.equals(HISTOGRAM_INTERFACE)) {
            return MetricType.HISTOGRAM;
        }
        if (name.equals(SIMPLE_TIMER_INTERFACE)) {
            return MetricType.SIMPLE_TIMER;
        }
        if (name.equals(TIMER_INTERFACE)) {
            return MetricType.TIMER;
        }
        if (name.equals(METER_INTERFACE)) {
            return MetricType.METERED;
        }
        return null;
    }

    private boolean isJaxRsEndpoint(ClassInfo clazz) {
        return clazz.annotations().containsKey(SmallRyeMetricsDotNames.JAXRS_PATH) ||
                clazz.annotations().containsKey(SmallRyeMetricsDotNames.REST_CONTROLLER);
    }

    private boolean isJaxRsProvider(ClassInfo clazz) {
        return clazz.annotations().containsKey(SmallRyeMetricsDotNames.JAXRS_PROVIDER);
    }

    private void collectMetricsClassAndSubClasses(IndexView index, Map<DotName, ClassInfo> collectedMetricsClasses,
            ClassInfo clazz) {
        collectedMetricsClasses.put(clazz.name(), clazz);
        for (ClassInfo subClass : index.getAllKnownSubclasses(clazz.name())) {
            collectedMetricsClasses.put(subClass.name(), subClass);
        }
    }
}
