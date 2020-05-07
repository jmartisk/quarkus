package io.quarkus.smallrye.graphql.deployment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.configuration.ConfigurationError;
import io.quarkus.deployment.index.ClassPathArtifactResolver;
import io.quarkus.deployment.index.ResolvedArtifact;
import io.quarkus.deployment.util.FileUtil;
import io.quarkus.deployment.util.ServiceUtil;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLRecorder;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RequireBodyHandlerBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.devmode.NotFoundPageDisplayableEndpointBuildItem;
import io.quarkus.vertx.http.runtime.HandlerType;
import io.smallrye.graphql.cdi.config.ConfigKey;
import io.smallrye.graphql.cdi.config.GraphQLConfig;
import io.smallrye.graphql.cdi.producer.GraphQLProducer;
import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.SchemaBuilder;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.schema.model.Reference;
import io.smallrye.graphql.schema.model.Schema;
import io.smallrye.graphql.spi.LookupService;
import io.smallrye.graphql.spi.MetricsService;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Processor for Smallrye GraphQL.
 * We scan all annotations and build the model during build.
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class SmallryeGraphqlProcessor {

    private static final Logger LOG = Logger.getLogger(SmallryeGraphqlProcessor.class.getName());
    private static final String SCHEMA_PATH = "/schema.graphql";
    private static final String SPI_PATH = "META-INF/services/";

    @Inject
    private LaunchModeBuildItem launchMode;

    /**
     * The configuration
     */
    SmallRyeGraphQLConfig quarkusConfig;

    @ConfigRoot(name = "smallrye-graphql")
    static final class SmallRyeGraphQLConfig {
        /**
         * The rootPath under which queries will be served. Default to /graphql
         */
        @ConfigItem(defaultValue = "/graphql")
        String rootPath;

        /**
         * The path where GraphQL UI is available.
         * <p>
         * The value `/` is not allowed as it blocks the application from serving anything else.
         */
        @ConfigItem(defaultValue = "/graphql-ui")
        String rootPathUi;

        /**
         * Always include the UI. By default this will only be included in dev and test.
         * Setting this to true will also include the UI in Prod
         */
        @ConfigItem(defaultValue = "false")
        boolean alwaysIncludeUi;

        /**
         * If GraphQL UI should be enabled. By default, GraphQL UI is enabled.
         */
        @ConfigItem(defaultValue = "true")
        boolean enableUi;
    }

    @BuildStep
    void feature(BuildProducer<FeatureBuildItem> featureProducer) {
        featureProducer.produce(new FeatureBuildItem(FeatureBuildItem.SMALLRYE_GRAPHQL));
    }

    @BuildStep
    void additionalBeanDefiningAnnotation(BuildProducer<BeanDefiningAnnotationBuildItem> beanDefiningAnnotationProducer) {
        // Make ArC discover the beans marked with the @GraphQlApi qualifier
        beanDefiningAnnotationProducer.produce(new BeanDefiningAnnotationBuildItem(Annotations.GRAPHQL_API));
    }

    @BuildStep
    void additionalBean(BuildProducer<AdditionalBeanBuildItem> additionalBeanProducer) {
        additionalBeanProducer.produce(AdditionalBeanBuildItem.builder()
                .addBeanClass(GraphQLConfig.class)
                .addBeanClass(GraphQLProducer.class)
                .setUnremovable().build());
    }

    @BuildStep
    void registerNativeImageResources(BuildProducer<ServiceProviderBuildItem> serviceProvider) throws IOException {
        // Lookup Service (We use the one from the CDI Module)
        String lookupService = SPI_PATH + LookupService.class.getName();
        Set<String> lookupImplementations = ServiceUtil.classNamesNamedIn(Thread.currentThread().getContextClassLoader(),
                lookupService);
        serviceProvider.produce(
                new ServiceProviderBuildItem(LookupService.class.getName(), lookupImplementations.toArray(new String[0])));

        // Metrics (We use the one from the CDI Module)
        String metricsService = SPI_PATH + MetricsService.class.getName();
        Set<String> metricsImplementations = ServiceUtil.classNamesNamedIn(Thread.currentThread().getContextClassLoader(),
                metricsService);
        serviceProvider.produce(
                new ServiceProviderBuildItem(MetricsService.class.getName(), metricsImplementations.toArray(new String[0])));
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void buildExecutionService(BuildProducer<ReflectiveClassBuildItem> reflectiveClassProducer,
            SmallRyeGraphQLRecorder recorder,
            CombinedIndexBuildItem combinedIndex) {

        IndexView index = combinedIndex.getIndex();
        Schema schema = SchemaBuilder.build(index);

        reflectiveClassProducer
                .produce(new ReflectiveClassBuildItem(true, true, getClassesToRegisterForReflection(schema)));

        recorder.createExecutionService(schema);
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    void buildEndpoints(
            BuildProducer<RequireBodyHandlerBuildItem> requireBodyHandlerProducer,
            BuildProducer<RouteBuildItem> routeProducer,
            BuildProducer<NotFoundPageDisplayableEndpointBuildItem> notFoundPageDisplayableEndpointProducer,
            //BuildProducer<ExtensionSslNativeSupportBuildItem> extensionSslNativeSupportProducer,
            SmallRyeGraphQLRecorder recorder,
            ShutdownContextBuildItem shutdownContext) throws IOException {

        /*
         * <em>Ugly Hack</em>
         * In dev mode, we pass a classloader to use in the CDI Loader.
         * This hack is required because using the TCCL would get an outdated version - the initial one.
         * This is because the worker thread on which the handler is called captures the TCCL at creation time
         * and does not allow updating it.
         *
         * In non dev mode, the TCCL is used.
         */
        if (launchMode.getLaunchMode() == LaunchMode.DEVELOPMENT) {
            recorder.setupClDevMode(shutdownContext);
        }
        // add graphql endpoint for not found display in dev or test mode
        if (launchMode.getLaunchMode().isDevOrTest()) {
            notFoundPageDisplayableEndpointProducer
                    .produce(new NotFoundPageDisplayableEndpointBuildItem(quarkusConfig.rootPath));
            notFoundPageDisplayableEndpointProducer
                    .produce(new NotFoundPageDisplayableEndpointBuildItem(quarkusConfig.rootPath + SCHEMA_PATH));
        }

        Boolean allowGet = ConfigProvider.getConfig().getOptionalValue(ConfigKey.ALLOW_GET, boolean.class).orElse(false);

        Handler<RoutingContext> executionHandler = recorder.executionHandler(allowGet);
        routeProducer.produce(new RouteBuildItem(quarkusConfig.rootPath, executionHandler, HandlerType.BLOCKING));

        Handler<RoutingContext> schemaHandler = recorder.schemaHandler();
        routeProducer.produce(
                new RouteBuildItem(quarkusConfig.rootPath + SCHEMA_PATH, schemaHandler, HandlerType.BLOCKING));

        // Because we need to read the body
        requireBodyHandlerProducer.produce(new RequireBodyHandlerBuildItem());

        // TODO: SSL Support by default ?
        // extensionSslNativeSupportProducer.produce(new ExtensionSslNativeSupportBuildItem(FeatureBuildItem.SMALLRYE_GRAPHQL));
    }

    private String[] getClassesToRegisterForReflection(Schema schema) {
        // Unique list of classes we need to do reflection on
        Set<String> classes = new HashSet<>();

        classes.addAll(getOperationClassNames(schema.getQueries()));
        classes.addAll(getOperationClassNames(schema.getMutations()));
        classes.addAll(getReferenceClassNames(schema.getTypes().values()));
        classes.addAll(getReferenceClassNames(schema.getInputs().values()));
        classes.addAll(getReferenceClassNames(schema.getInterfaces().values()));

        // TODO: Introspection classes. (In native mode, the graphiQL introspection query fails. Below is not working)
//        classes.add(GraphQLObjectType.class.getName());
//        classes.add(GraphQLInputObjectType.class.getName());
//        classes.add(GraphQLEnumType.class.getName());
//        classes.add(GraphQLInputObjectField.class.getName());
//        classes.add(GraphQLInterfaceType.class.getName());

        String[] arrayOfClassNames = classes.toArray(new String[] {});
        return arrayOfClassNames;
    }

    private Set<String> getOperationClassNames(Set<Operation> operations) {
        Set<String> classes = new HashSet<>();
        for (Operation operation : operations) {
            classes.add(operation.getClassName());
        }
        return classes;
    }

    private Set<String> getReferenceClassNames(Collection complexGraphQLTypes) {
        Set<String> classes = new HashSet<>();
        for (Object complexGraphQLType : complexGraphQLTypes) {
            Reference reference = Reference.class.cast(complexGraphQLType);
            classes.add(reference.getClassName());
        }
        return classes;
    }

    // For the UI
    private static final String GRAPHQL_UI_WEBJAR_GROUP_ID = "io.smallrye";
    private static final String GRAPHQL_UI_WEBJAR_ARTIFACT_ID = "smallrye-graphql-ui-graphiql";
    private static final String GRAPHQL_UI_WEBJAR_PREFIX = "META-INF/resources/graphql-ui";
    private static final String OWN_MEDIA_FOLDER = "META-INF/resources/";
    private static final String GRAPHQL_UI_FINAL_DESTINATION = "META-INF/graphql-ui-files";
    private static final String TEMP_DIR_PREFIX = "quarkus-graphql-ui_" + System.nanoTime();
    private static final List<String> IGNORE_LIST = Arrays.asList(new String[] { "logo.png", "favicon.ico" });
    private static final String FILE_TO_UPDATE = "render.js";

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerGraphQLUiServletExtension(
            BuildProducer<RouteBuildItem> routeProducer,
            BuildProducer<GeneratedResourceBuildItem> generatedResourceProducer,
            BuildProducer<NativeImageResourceBuildItem> nativeImageResourceProducer,
            BuildProducer<NotFoundPageDisplayableEndpointBuildItem> notFoundPageDisplayableEndpointProducer,
            SmallRyeGraphQLRecorder recorder,
            LiveReloadBuildItem liveReload,
            HttpRootPathBuildItem httpRootPath) throws Exception {

        if (!quarkusConfig.enableUi) {
            return;
        }
        if ("/".equals(quarkusConfig.rootPathUi)) {
            throw new ConfigurationError(
                    "quarkus.smallrye-graphql.root-path-ui was set to \"/\", this is not allowed as it blocks the application from serving anything else.");
        }

        String graphQLPath = httpRootPath.adjustPath(quarkusConfig.rootPath);

        if (launchMode.getLaunchMode().isDevOrTest()) {
            CachedGraphQLUI cached = liveReload.getContextObject(CachedGraphQLUI.class);
            boolean extractionNeeded = cached == null;

            if (cached != null && !cached.cachedGraphQLPath.equals(graphQLPath)) {
                try {
                    FileUtil.deleteDirectory(Paths.get(cached.cachedDirectory));
                } catch (IOException e) {
                    LOG.error("Failed to clean GraphQL UI temp directory on restart", e);
                }
                extractionNeeded = true;
            }
            if (extractionNeeded) {
                if (cached == null) {
                    cached = new CachedGraphQLUI();
                    liveReload.setContextObject(CachedGraphQLUI.class, cached);
                    Runtime.getRuntime().addShutdownHook(new Thread(cached, "GraphQL UI Shutdown Hook"));
                }
                try {
                    ResolvedArtifact artifact = getGraphQLUiArtifact();
                    Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX).toRealPath();
                    extractGraphQLUi(artifact, tempDir);
                    updateApiUrl(tempDir.resolve(FILE_TO_UPDATE), graphQLPath);
                    cached.cachedDirectory = tempDir.toAbsolutePath().toString();
                    cached.cachedGraphQLPath = graphQLPath;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            Handler<RoutingContext> handler = recorder.uiHandler(cached.cachedDirectory,
                    httpRootPath.adjustPath(quarkusConfig.rootPathUi));
            routeProducer.produce(new RouteBuildItem(quarkusConfig.rootPathUi, handler));
            routeProducer.produce(new RouteBuildItem(quarkusConfig.rootPathUi + "/*", handler));
            notFoundPageDisplayableEndpointProducer
                    .produce(new NotFoundPageDisplayableEndpointBuildItem(quarkusConfig.rootPathUi + "/"));
        } else if (quarkusConfig.alwaysIncludeUi) {
            ResolvedArtifact artifact = getGraphQLUiArtifact();
            //we are including in a production artifact
            //just stick the files in the generated output
            //we could do this for dev mode as well but then we need to extract them every time
            File artifactFile = artifact.getArtifactPath().toFile();
            try (JarFile jarFile = new JarFile(artifactFile)) {
                Enumeration<JarEntry> entries = jarFile.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith(GRAPHQL_UI_WEBJAR_PREFIX) && !entry.isDirectory()) {
                        try (InputStream inputStream = jarFile.getInputStream(entry)) {
                            String filename = entry.getName().replace(GRAPHQL_UI_WEBJAR_PREFIX + "/", "");
                            byte[] content = FileUtil.readFileContents(inputStream);
                            if (entry.getName().endsWith(FILE_TO_UPDATE)) {
                                content = updateApiUrl(new String(content, StandardCharsets.UTF_8), graphQLPath)
                                        .getBytes(StandardCharsets.UTF_8);
                            }
                            if (IGNORE_LIST.contains(filename)) {
                                ClassLoader classLoader = SmallryeGraphqlProcessor.class.getClassLoader();
                                InputStream resourceAsStream = classLoader
                                        .getResourceAsStream(OWN_MEDIA_FOLDER + filename);
                                content = streamToByte(resourceAsStream);
                            }

                            String fileName = GRAPHQL_UI_FINAL_DESTINATION + "/" + filename;

                            generatedResourceProducer
                                    .produce(new GeneratedResourceBuildItem(fileName, content));

                            nativeImageResourceProducer
                                    .produce(new NativeImageResourceBuildItem(fileName));

                        }
                    }
                }
            }

            Handler<RoutingContext> handler = recorder
                    .uiHandler(GRAPHQL_UI_FINAL_DESTINATION, httpRootPath.adjustPath(quarkusConfig.rootPathUi));
            routeProducer.produce(new RouteBuildItem(quarkusConfig.rootPathUi, handler));
            routeProducer.produce(new RouteBuildItem(quarkusConfig.rootPathUi + "/*", handler));
        }
    }

    private byte[] streamToByte(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }

    private ResolvedArtifact getGraphQLUiArtifact() {
        ClassPathArtifactResolver resolver = new ClassPathArtifactResolver(SmallryeGraphqlProcessor.class.getClassLoader());
        return resolver.getArtifact(GRAPHQL_UI_WEBJAR_GROUP_ID, GRAPHQL_UI_WEBJAR_ARTIFACT_ID, null);
    }

    private void extractGraphQLUi(ResolvedArtifact artifact, Path resourceDir) throws IOException {
        File artifactFile = artifact.getArtifactPath().toFile();
        try (JarFile jarFile = new JarFile(artifactFile)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(GRAPHQL_UI_WEBJAR_PREFIX) && !entry.isDirectory()) {
                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        String filename = entry.getName().replace(GRAPHQL_UI_WEBJAR_PREFIX + "/", "");
                        if (!IGNORE_LIST.contains(filename)) {
                            Files.copy(inputStream, resourceDir.resolve(filename));
                        }
                    }
                }
            }
            // Now add our own logo and favicon
            ClassLoader classLoader = SmallryeGraphqlProcessor.class.getClassLoader();
            for (String ownMedia : IGNORE_LIST) {
                InputStream logo = classLoader.getResourceAsStream(OWN_MEDIA_FOLDER + ownMedia);
                Files.copy(logo, resourceDir.resolve(ownMedia));
            }
        }
    }

    private void updateApiUrl(Path renderJs, String graphqlPath) throws IOException {
        String content = new String(Files.readAllBytes(renderJs), StandardCharsets.UTF_8);
        String result = updateApiUrl(content, graphqlPath);
        if (result != null) {
            Files.write(renderJs, result.getBytes(StandardCharsets.UTF_8));
        }
    }

    public String updateApiUrl(String original, String graphqlPath) {
        return original.replace("const api = '/graphql';", "const api = '" + graphqlPath + "';");
    }

    private static final class CachedGraphQLUI implements Runnable {

        String cachedGraphQLPath;
        String cachedDirectory;

        @Override
        public void run() {
            try {
                FileUtil.deleteDirectory(Paths.get(cachedDirectory));
            } catch (IOException e) {
                LOG.error("Failed to clean GraphQL UI temp directory on shutdown", e);
            }
        }
    }
}
