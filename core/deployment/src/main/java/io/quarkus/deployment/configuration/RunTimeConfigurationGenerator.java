package io.quarkus.deployment.configuration;

import static io.quarkus.deployment.util.ReflectUtil.reportError;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.Converter;
import org.objectweb.asm.Opcodes;
import org.wildfly.common.Assert;

import io.quarkus.deployment.AccessorFinder;
import io.quarkus.deployment.configuration.definition.ClassDefinition;
import io.quarkus.deployment.configuration.definition.GroupDefinition;
import io.quarkus.deployment.configuration.definition.RootDefinition;
import io.quarkus.deployment.configuration.matching.ConfigPatternMap;
import io.quarkus.deployment.configuration.matching.Container;
import io.quarkus.deployment.configuration.matching.FieldContainer;
import io.quarkus.deployment.configuration.matching.MapContainer;
import io.quarkus.deployment.configuration.type.ArrayOf;
import io.quarkus.deployment.configuration.type.CollectionOf;
import io.quarkus.deployment.configuration.type.ConverterType;
import io.quarkus.deployment.configuration.type.Leaf;
import io.quarkus.deployment.configuration.type.LowerBoundCheckOf;
import io.quarkus.deployment.configuration.type.MinMaxValidated;
import io.quarkus.deployment.configuration.type.OptionalOf;
import io.quarkus.deployment.configuration.type.PatternValidated;
import io.quarkus.deployment.configuration.type.UpperBoundCheckOf;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.CatchBlockCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.TryBlock;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.configuration.AbstractRawDefaultConfigSource;
import io.quarkus.runtime.configuration.ConfigDiagnostic;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.runtime.configuration.HyphenateEnumConverter;
import io.quarkus.runtime.configuration.NameIterator;
import io.quarkus.runtime.configuration.ProfileManager;
import io.quarkus.runtime.configuration.QuarkusConfigFactory;
import io.smallrye.config.Converters;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 *
 */
public final class RunTimeConfigurationGenerator {

    public static final String CONFIG_CLASS_NAME = "io.quarkus.runtime.generated.Config";
    static final String RTDVCS_CLASS_NAME = "io.quarkus.runtime.generated.RunTimeDefaultValuesConfigSource";
    static final String BTRTDVCS_CLASS_NAME = "io.quarkus.runtime.generated.BuildTimeRunTimeDefaultValuesConfigSource";

    // member descriptors

    static final MethodDescriptor BTRTDVCS_NEW = MethodDescriptor.ofConstructor(BTRTDVCS_CLASS_NAME);

    static final FieldDescriptor C_BUILD_TIME_CONFIG_SOURCE = FieldDescriptor.of(CONFIG_CLASS_NAME, "buildTimeConfigSource",
            ConfigSource.class);
    static final FieldDescriptor C_BUILD_TIME_RUN_TIME_DEFAULTS_CONFIG_SOURCE = FieldDescriptor.of(CONFIG_CLASS_NAME,
            "buildTimeRunTimeDefaultsConfigSource", ConfigSource.class);
    public static final MethodDescriptor C_CREATE_RUN_TIME_CONFIG = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME,
            "createRunTimeConfig", void.class);
    public static final MethodDescriptor C_ENSURE_INITIALIZED = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME,
            "ensureInitialized", void.class);
    static final FieldDescriptor C_RUN_TIME_DEFAULTS_CONFIG_SOURCE = FieldDescriptor.of(CONFIG_CLASS_NAME,
            "runTimeDefaultsConfigSource", ConfigSource.class);
    static final MethodDescriptor C_READ_CONFIG = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME, "readConfig", void.class);
    static final FieldDescriptor C_SPECIFIED_RUN_TIME_CONFIG_SOURCE = FieldDescriptor.of(CONFIG_CLASS_NAME,
            "specifiedRunTimeConfigSource",
            ConfigSource.class);

    static final MethodDescriptor CD_INVALID_VALUE = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "invalidValue",
            void.class, String.class, IllegalArgumentException.class);
    static final MethodDescriptor CD_IS_ERROR = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "isError",
            boolean.class);
    static final MethodDescriptor CD_MISSING_VALUE = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "missingValue",
            void.class, String.class, NoSuchElementException.class);
    static final MethodDescriptor CD_RESET_ERROR = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "resetError", void.class);
    static final MethodDescriptor CD_UNKNOWN = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "unknown",
            void.class, NameIterator.class);
    static final MethodDescriptor CD_UNKNOWN_RT = MethodDescriptor.ofMethod(ConfigDiagnostic.class, "unknownRunTime",
            void.class, NameIterator.class);

    static final MethodDescriptor CONVS_NEW_ARRAY_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "newArrayConverter", Converter.class, Converter.class, Class.class);
    static final MethodDescriptor CONVS_NEW_COLLECTION_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "newCollectionConverter", Converter.class, Converter.class, IntFunction.class);
    static final MethodDescriptor CONVS_NEW_OPTIONAL_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "newOptionalConverter", Converter.class, Converter.class);
    static final MethodDescriptor CONVS_RANGE_VALUE_STRING_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "rangeValueStringConverter", Converter.class, Converter.class, String.class, boolean.class, String.class,
            boolean.class);
    static final MethodDescriptor CONVS_MINIMUM_VALUE_STRING_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "minimumValueStringConverter", Converter.class, Converter.class, String.class, boolean.class);
    static final MethodDescriptor CONVS_MAXIMUM_VALUE_STRING_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "maximumValueStringConverter", Converter.class, Converter.class, String.class, boolean.class);
    static final MethodDescriptor CONVS_PATTERN_CONVERTER = MethodDescriptor.ofMethod(Converters.class,
            "patternConverter", Converter.class, Converter.class, Pattern.class);

    static final MethodDescriptor CPR_GET_CONFIG = MethodDescriptor.ofMethod(ConfigProviderResolver.class, "getConfig",
            Config.class);
    static final MethodDescriptor CPR_INSTANCE = MethodDescriptor.ofMethod(ConfigProviderResolver.class, "instance",
            ConfigProviderResolver.class);
    static final MethodDescriptor CPR_RELEASE_CONFIG = MethodDescriptor.ofMethod(ConfigProviderResolver.class, "releaseConfig",
            void.class, Config.class);

    static final MethodDescriptor CU_LIST_FACTORY = MethodDescriptor.ofMethod(ConfigUtils.class, "listFactory",
            IntFunction.class);
    static final MethodDescriptor CU_SET_FACTORY = MethodDescriptor.ofMethod(ConfigUtils.class, "setFactory",
            IntFunction.class);
    static final MethodDescriptor CU_SORTED_SET_FACTORY = MethodDescriptor.ofMethod(ConfigUtils.class, "sortedSetFactory",
            IntFunction.class);
    static final MethodDescriptor CU_CONFIG_BUILDER = MethodDescriptor.ofMethod(ConfigUtils.class, "configBuilder",
            SmallRyeConfigBuilder.class, boolean.class);
    static final MethodDescriptor CU_ADD_SOURCE_PROVIDER = MethodDescriptor.ofMethod(ConfigUtils.class, "addSourceProvider",
            void.class, SmallRyeConfigBuilder.class, ConfigSourceProvider.class);

    static final MethodDescriptor HM_NEW = MethodDescriptor.ofConstructor(HashMap.class);
    static final MethodDescriptor HM_PUT = MethodDescriptor.ofMethod(HashMap.class, "put", Object.class, Object.class,
            Object.class);

    static final MethodDescriptor ITRA_ITERATOR = MethodDescriptor.ofMethod(Iterable.class, "iterator", Iterator.class);

    static final MethodDescriptor ITR_HAS_NEXT = MethodDescriptor.ofMethod(Iterator.class, "hasNext", boolean.class);
    static final MethodDescriptor ITR_NEXT = MethodDescriptor.ofMethod(Iterator.class, "next", Object.class);

    static final MethodDescriptor MAP_GET = MethodDescriptor.ofMethod(Map.class, "get", Object.class, Object.class);
    static final MethodDescriptor MAP_PUT = MethodDescriptor.ofMethod(Map.class, "put", Object.class, Object.class,
            Object.class);

    static final MethodDescriptor NI_GET_ALL_PREVIOUS_SEGMENTS = MethodDescriptor.ofMethod(NameIterator.class,
            "getAllPreviousSegments", String.class);
    static final MethodDescriptor NI_GET_NAME = MethodDescriptor.ofMethod(NameIterator.class, "getName", String.class);
    static final MethodDescriptor NI_GET_PREVIOUS_SEGMENT = MethodDescriptor.ofMethod(NameIterator.class, "getPreviousSegment",
            String.class);
    static final MethodDescriptor NI_HAS_NEXT = MethodDescriptor.ofMethod(NameIterator.class, "hasNext", boolean.class);
    static final MethodDescriptor NI_NEW_STRING = MethodDescriptor.ofConstructor(NameIterator.class, String.class);
    static final MethodDescriptor NI_NEXT_EQUALS = MethodDescriptor.ofMethod(NameIterator.class, "nextSegmentEquals",
            boolean.class, String.class);
    static final MethodDescriptor NI_NEXT = MethodDescriptor.ofMethod(NameIterator.class, "next", void.class);
    static final MethodDescriptor NI_PREVIOUS = MethodDescriptor.ofMethod(NameIterator.class, "previous", void.class);
    static final MethodDescriptor NI_PREVIOUS_EQUALS = MethodDescriptor.ofMethod(NameIterator.class, "previousSegmentEquals",
            boolean.class, String.class);

    static final MethodDescriptor OBJ_TO_STRING = MethodDescriptor.ofMethod(Object.class, "toString", String.class);

    static final MethodDescriptor OPT_EMPTY = MethodDescriptor.ofMethod(Optional.class, "empty", Optional.class);
    static final MethodDescriptor OPT_GET = MethodDescriptor.ofMethod(Optional.class, "get", Object.class);
    static final MethodDescriptor OPT_IS_PRESENT = MethodDescriptor.ofMethod(Optional.class, "isPresent", boolean.class);
    static final MethodDescriptor OPT_OF = MethodDescriptor.ofMethod(Optional.class, "of", Optional.class, Object.class);

    static final MethodDescriptor PCS_NEW = MethodDescriptor.ofConstructor(PropertiesConfigSource.class,
            Map.class, String.class, int.class);

    static final MethodDescriptor PM_SET_RUNTIME_DEFAULT_PROFILE = MethodDescriptor.ofMethod(ProfileManager.class,
            "setRuntimeDefaultProfile", void.class, String.class);

    static final MethodDescriptor SB_NEW = MethodDescriptor.ofConstructor(StringBuilder.class);
    static final MethodDescriptor SB_NEW_STR = MethodDescriptor.ofConstructor(StringBuilder.class, String.class);
    static final MethodDescriptor SB_APPEND_STRING = MethodDescriptor.ofMethod(StringBuilder.class, "append",
            StringBuilder.class, String.class);
    static final MethodDescriptor SB_APPEND_CHAR = MethodDescriptor.ofMethod(StringBuilder.class, "append",
            StringBuilder.class, char.class);
    static final MethodDescriptor SB_LENGTH = MethodDescriptor.ofMethod(StringBuilder.class, "length",
            int.class);
    static final MethodDescriptor SB_SET_LENGTH = MethodDescriptor.ofMethod(StringBuilder.class, "setLength",
            void.class, int.class);

    static final MethodDescriptor QCF_SET_CONFIG = MethodDescriptor.ofMethod(QuarkusConfigFactory.class, "setConfig",
            void.class, SmallRyeConfig.class);

    static final MethodDescriptor RTDVCS_NEW = MethodDescriptor.ofConstructor(RTDVCS_CLASS_NAME);

    static final MethodDescriptor SRC_GET_CONVERTER = MethodDescriptor.ofMethod(SmallRyeConfig.class, "getConverter",
            Converter.class, Class.class);
    static final MethodDescriptor SRC_GET_PROPERTY_NAMES = MethodDescriptor.ofMethod(SmallRyeConfig.class, "getPropertyNames",
            Iterable.class);
    static final MethodDescriptor SRC_GET_VALUE = MethodDescriptor.ofMethod(SmallRyeConfig.class, "getValue",
            Object.class, String.class, Converter.class);

    static final MethodDescriptor SRCB_WITH_CONVERTER = MethodDescriptor.ofMethod(SmallRyeConfigBuilder.class,
            "withConverter", ConfigBuilder.class, Class.class, int.class, Converter.class);
    static final MethodDescriptor SRCB_WITH_SOURCES = MethodDescriptor.ofMethod(SmallRyeConfigBuilder.class,
            "withSources", ConfigBuilder.class, ConfigSource[].class);
    static final MethodDescriptor SRCB_BUILD = MethodDescriptor.ofMethod(SmallRyeConfigBuilder.class, "build",
            SmallRyeConfig.class);

    // todo: more space-efficient sorted map impl
    static final MethodDescriptor TM_NEW = MethodDescriptor.ofConstructor(TreeMap.class);

    private RunTimeConfigurationGenerator() {
    }

    public static void generate(BuildTimeConfigurationReader.ReadResult readResult, final ClassOutput classOutput,
            final Map<String, String> runTimeDefaults, List<Class<?>> additionalTypes) {
        new GenerateOperation.Builder().setBuildTimeReadResult(readResult).setClassOutput(classOutput)
                .setRunTimeDefaults(runTimeDefaults).setAdditionalTypes(additionalTypes).build().run();
    }

    static final class GenerateOperation implements AutoCloseable {
        final AccessorFinder accessorFinder;
        final ClassOutput classOutput;
        final ClassCreator cc;
        final MethodCreator clinit;
        final BytecodeCreator converterSetup;
        final MethodCreator readConfig;
        final ResultHandle readConfigNameBuilder;
        final ResultHandle clinitNameBuilder;
        final BuildTimeConfigurationReader.ReadResult buildTimeConfigResult;
        final ConfigPatternMap<Container> runTimePatternMap;
        final List<RootDefinition> roots;
        // default values given in the build configuration
        final Map<String, String> specifiedRunTimeDefaultValues;
        final Map<String, String> buildTimeRunTimeVisibleValues;
        // default values produced by extensions via build item
        final Map<String, String> runTimeDefaults;
        final Map<Container, MethodDescriptor> enclosingMemberMethods = new HashMap<>();
        final Map<Class<?>, MethodDescriptor> groupInitMethods = new HashMap<>();
        final Map<Class<?>, FieldDescriptor> configRootsByType = new HashMap<>();
        final ResultHandle clinitConfig;
        final Map<FieldDescriptor, Class<?>> convertersToRegister = new HashMap<>();
        final List<Class<?>> additionalTypes;
        /**
         * Regular converters organized by type. Each converter is stored in a separate field. Some are used
         * only at build time, some only at run time, and some at both times.
         * Producing a native image will automatically delete the converters which are not used at run time from the
         * final image.
         */
        final Map<ConverterType, FieldDescriptor> convertersByType = new HashMap<>();
        /**
         * Cache of things created in `clinit` which are then stored in fields, including config roots and converter
         * instances. The result handles are usable only from `clinit`.
         */
        final Map<FieldDescriptor, ResultHandle> instanceCache = new HashMap<>();
        /**
         * Converter fields have numeric names to keep space down.
         */
        int converterIndex = 0;

        GenerateOperation(Builder builder) {
            final BuildTimeConfigurationReader.ReadResult buildTimeReadResult = builder.buildTimeReadResult;
            buildTimeConfigResult = Assert.checkNotNullParam("buildTimeReadResult", buildTimeReadResult);
            specifiedRunTimeDefaultValues = Assert.checkNotNullParam("specifiedRunTimeDefaultValues",
                    buildTimeReadResult.getSpecifiedRunTimeDefaultValues());
            buildTimeRunTimeVisibleValues = Assert.checkNotNullParam("buildTimeRunTimeVisibleValues",
                    buildTimeReadResult.getBuildTimeRunTimeVisibleValues());
            classOutput = Assert.checkNotNullParam("classOutput", builder.getClassOutput());
            roots = Assert.checkNotNullParam("builder.roots", builder.getBuildTimeReadResult().getAllRoots());
            runTimeDefaults = Assert.checkNotNullParam("runTimeDefaults", builder.getRunTimeDefaults());
            additionalTypes = Assert.checkNotNullParam("additionalTypes", builder.getAdditionalTypes());
            cc = ClassCreator.builder().classOutput(classOutput).className(CONFIG_CLASS_NAME).setFinal(true).build();
            // not instantiable
            try (MethodCreator mc = cc.getMethodCreator(MethodDescriptor.ofConstructor(CONFIG_CLASS_NAME))) {
                mc.setModifiers(Opcodes.ACC_PRIVATE);
                mc.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), mc.getThis());
                mc.returnValue(null);
            }

            // create <clinit>
            clinit = cc.getMethodCreator(MethodDescriptor.ofMethod(CONFIG_CLASS_NAME, "<clinit>", void.class));
            clinit.setModifiers(Opcodes.ACC_STATIC);
            clinit.invokeStaticMethod(PM_SET_RUNTIME_DEFAULT_PROFILE, clinit.load(ProfileManager.getActiveProfile()));
            clinitNameBuilder = clinit.newInstance(SB_NEW);
            clinit.invokeVirtualMethod(SB_APPEND_STRING, clinitNameBuilder, clinit.load("quarkus"));

            // create the map for build time config source
            final ResultHandle buildTimeValues = clinit.newInstance(HM_NEW);
            for (Map.Entry<String, String> entry : buildTimeRunTimeVisibleValues.entrySet()) {
                clinit.invokeVirtualMethod(HM_PUT, buildTimeValues, clinit.load(entry.getKey()), clinit.load(entry.getValue()));
            }

            // the build time config source field, to feed into the run time config
            cc.getFieldCreator(C_BUILD_TIME_CONFIG_SOURCE)
                    .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            final ResultHandle buildTimeConfigSource = clinit.newInstance(PCS_NEW, buildTimeValues,
                    clinit.load("Build time config"), clinit.load(100));
            clinit.writeStaticField(C_BUILD_TIME_CONFIG_SOURCE, buildTimeConfigSource);

            // the build time run time visible default values config source
            cc.getFieldCreator(C_BUILD_TIME_RUN_TIME_DEFAULTS_CONFIG_SOURCE)
                    .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            final ResultHandle buildTimeRunTimeDefaultValuesConfigSource = clinit.newInstance(BTRTDVCS_NEW);
            clinit.writeStaticField(C_BUILD_TIME_RUN_TIME_DEFAULTS_CONFIG_SOURCE, buildTimeRunTimeDefaultValuesConfigSource);

            // the run time default values config source
            cc.getFieldCreator(C_RUN_TIME_DEFAULTS_CONFIG_SOURCE)
                    .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            clinit.writeStaticField(C_RUN_TIME_DEFAULTS_CONFIG_SOURCE, clinit.newInstance(RTDVCS_NEW));

            // the build time config, which is for user use only (not used by us other than for loading converters)
            final ResultHandle buildTimeBuilder = clinit.invokeStaticMethod(CU_CONFIG_BUILDER, clinit.load(true));
            final ResultHandle array = clinit.newArray(ConfigSource[].class, clinit.load(2));
            // build time values
            clinit.writeArrayValue(array, 0, buildTimeConfigSource);
            // build time defaults
            clinit.writeArrayValue(array, 1, buildTimeRunTimeDefaultValuesConfigSource);
            clinit.invokeVirtualMethod(SRCB_WITH_SOURCES, buildTimeBuilder, array);
            clinitConfig = clinit.checkCast(clinit.invokeVirtualMethod(SRCB_BUILD, buildTimeBuilder),
                    SmallRyeConfig.class);

            // block for converter setup
            converterSetup = clinit.createScope();
            // create readConfig
            readConfig = cc.getMethodCreator(C_READ_CONFIG);
            // the readConfig name builder
            readConfigNameBuilder = readConfig.newInstance(SB_NEW);
            readConfig.invokeVirtualMethod(SB_APPEND_STRING, readConfigNameBuilder, readConfig.load("quarkus"));
            runTimePatternMap = buildTimeReadResult.getRunTimePatternMap();
            accessorFinder = new AccessorFinder();
        }

        public void run() {
            // in clinit, load the build-time config

            // make the build time config global until we read the run time config -
            // at run time (when we're ready) we update the factory and then release the build time config
            clinit.invokeStaticMethod(QCF_SET_CONFIG, clinitConfig);
            // release any previous configuration
            final ResultHandle clinitCpr = clinit.invokeStaticMethod(CPR_INSTANCE);
            try (TryBlock getConfigTry = clinit.tryBlock()) {
                final ResultHandle initialConfigHandle = getConfigTry.invokeVirtualMethod(CPR_GET_CONFIG,
                        clinitCpr);
                getConfigTry.invokeVirtualMethod(CPR_RELEASE_CONFIG, clinitCpr, initialConfigHandle);
                // ignore
                getConfigTry.addCatch(IllegalStateException.class);
            }

            // fill roots map
            for (RootDefinition root : roots) {
                configRootsByType.put(root.getConfigurationClass(), root.getDescriptor());
            }

            // generate the parse methods and populate converters

            final ConfigPatternMap<Container> buildTimePatternMap = buildTimeConfigResult.getBuildTimePatternMap();
            final ConfigPatternMap<Container> buildTimeRunTimePatternMap = buildTimeConfigResult
                    .getBuildTimeRunTimePatternMap();
            final ConfigPatternMap<Container> runTimePatternMap = buildTimeConfigResult.getRunTimePatternMap();

            final BiFunction<Container, Container, Container> combinator = (a, b) -> a == null ? b : a;
            final ConfigPatternMap<Container> buildTimeRunTimeIgnored = ConfigPatternMap.merge(buildTimePatternMap,
                    runTimePatternMap, combinator);
            final ConfigPatternMap<Container> runTimeIgnored = ConfigPatternMap.merge(buildTimePatternMap,
                    buildTimeRunTimePatternMap, combinator);

            final MethodDescriptor siParserBody = generateParserBody(buildTimeRunTimePatternMap, buildTimeRunTimeIgnored,
                    new StringBuilder("siParseKey"), false, false);
            final MethodDescriptor rtParserBody = generateParserBody(runTimePatternMap, runTimeIgnored,
                    new StringBuilder("rtParseKey"), false, true);

            // create the run time config
            final ResultHandle runTimeBuilder = readConfig.invokeStaticMethod(CU_CONFIG_BUILDER, readConfig.load(true));

            // add in our run time only config source provider
            readConfig.invokeStaticMethod(CU_ADD_SOURCE_PROVIDER, runTimeBuilder, readConfig.newInstance(
                    MethodDescriptor.ofConstructor("io.quarkus.runtime.generated.ConfigSourceProviderImpl")));

            // create the map for run time specified values config source
            final ResultHandle specifiedRunTimeValues = clinit.newInstance(HM_NEW);
            for (Map.Entry<String, String> entry : specifiedRunTimeDefaultValues.entrySet()) {
                clinit.invokeVirtualMethod(HM_PUT, specifiedRunTimeValues, clinit.load(entry.getKey()),
                        clinit.load(entry.getValue()));
            }
            for (Map.Entry<String, String> entry : runTimeDefaults.entrySet()) {
                if (!specifiedRunTimeDefaultValues.containsKey(entry.getKey())) {
                    // only add entry if the user didn't override it
                    clinit.invokeVirtualMethod(HM_PUT, specifiedRunTimeValues, clinit.load(entry.getKey()),
                            clinit.load(entry.getValue()));
                }
            }
            final ResultHandle specifiedRunTimeSource = clinit.newInstance(PCS_NEW, specifiedRunTimeValues,
                    clinit.load("Specified default values"), clinit.load(Integer.MIN_VALUE + 100));
            cc.getFieldCreator(C_SPECIFIED_RUN_TIME_CONFIG_SOURCE).setModifiers(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            clinit.writeStaticField(C_SPECIFIED_RUN_TIME_CONFIG_SOURCE, specifiedRunTimeSource);

            // add in our custom sources
            final ResultHandle array = readConfig.newArray(ConfigSource[].class, readConfig.load(4));
            // build time config (expanded values)
            readConfig.writeArrayValue(array, 0, readConfig.readStaticField(C_BUILD_TIME_CONFIG_SOURCE));
            // specified run time config default values
            readConfig.writeArrayValue(array, 1, readConfig.readStaticField(C_SPECIFIED_RUN_TIME_CONFIG_SOURCE));
            // run time config default values
            readConfig.writeArrayValue(array, 2, readConfig.readStaticField(C_RUN_TIME_DEFAULTS_CONFIG_SOURCE));
            // build time run time visible default config source
            readConfig.writeArrayValue(array, 3, readConfig.readStaticField(C_BUILD_TIME_RUN_TIME_DEFAULTS_CONFIG_SOURCE));

            // add in known converters
            for (Class<?> additionalType : additionalTypes) {
                ConverterType type = new Leaf(additionalType, null);
                FieldDescriptor fd = convertersByType.get(type);
                if (fd == null) {
                    // it's an unknown
                    final ResultHandle clazzHandle = converterSetup.loadClass(additionalType);
                    fd = FieldDescriptor.of(cc.getClassName(), "conv$" + converterIndex++, Converter.class);
                    ResultHandle converter = converterSetup.invokeVirtualMethod(SRC_GET_CONVERTER, clinitConfig, clazzHandle);
                    cc.getFieldCreator(fd).setModifiers(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
                    converterSetup.writeStaticField(fd, converter);
                    convertersByType.put(type, fd);
                    instanceCache.put(fd, converter);
                    convertersToRegister.put(fd, additionalType);
                }
            }
            if (!convertersToRegister.isEmpty()) {
                for (Map.Entry<FieldDescriptor, Class<?>> entry : convertersToRegister.entrySet()) {
                    final FieldDescriptor descriptor = entry.getKey();
                    final Class<?> type = entry.getValue();
                    readConfig.invokeVirtualMethod(SRCB_WITH_CONVERTER, runTimeBuilder, readConfig.loadClass(type),
                            readConfig.load(100), readConfig.readStaticField(descriptor));
                }
            }

            // put them in the builder
            readConfig.invokeVirtualMethod(SRCB_WITH_SOURCES, runTimeBuilder, array);

            final ResultHandle runTimeConfig = readConfig.invokeVirtualMethod(SRCB_BUILD, runTimeBuilder);
            // install run time config
            readConfig.invokeStaticMethod(QCF_SET_CONFIG, runTimeConfig);
            // now invalidate the cached config, so the next one to load the config gets the new one
            final ResultHandle configProviderResolver = readConfig.invokeStaticMethod(CPR_INSTANCE);
            try (TryBlock getConfigTry = readConfig.tryBlock()) {
                final ResultHandle initialConfigHandle = getConfigTry.invokeVirtualMethod(CPR_GET_CONFIG,
                        configProviderResolver);
                getConfigTry.invokeVirtualMethod(CPR_RELEASE_CONFIG, configProviderResolver, initialConfigHandle);
                // ignore
                getConfigTry.addCatch(IllegalStateException.class);
            }

            final ResultHandle clInitOldLen = clinit.invokeVirtualMethod(SB_LENGTH, clinitNameBuilder);
            final ResultHandle rcOldLen = readConfig.invokeVirtualMethod(SB_LENGTH, readConfigNameBuilder);

            // generate eager config read (both build and run time at once)
            for (RootDefinition root : roots) {
                // common things for all config phases
                final Class<?> configurationClass = root.getConfigurationClass();
                FieldDescriptor rootFieldDescriptor = root.getDescriptor();

                // Get or generate group init method
                MethodDescriptor initGroup = generateInitGroup(root);

                final MethodDescriptor ctor = accessorFinder
                        .getConstructorFor(MethodDescriptor.ofConstructor(configurationClass));

                // specific actions based on config phase
                String rootName = root.getRootName();
                if (root.getConfigPhase() == ConfigPhase.BUILD_AND_RUN_TIME_FIXED) {
                    // config root field is final; we initialize it from clinit
                    cc.getFieldCreator(rootFieldDescriptor)
                            .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
                    // construct instance in <clinit>
                    final ResultHandle instance = clinit.invokeStaticMethod(ctor);
                    // assign instance to field
                    clinit.writeStaticField(rootFieldDescriptor, instance);
                    instanceCache.put(rootFieldDescriptor, instance);
                    // eager init as appropriate
                    if (!rootName.isEmpty()) {
                        clinit.invokeVirtualMethod(SB_APPEND_CHAR, clinitNameBuilder, clinit.load('.'));
                        clinit.invokeVirtualMethod(SB_APPEND_STRING, clinitNameBuilder, clinit.load(rootName));
                    }
                    clinit.invokeStaticMethod(initGroup, clinitConfig, clinitNameBuilder, instance);
                    clinit.invokeVirtualMethod(SB_SET_LENGTH, clinitNameBuilder, clInitOldLen);
                } else if (root.getConfigPhase() == ConfigPhase.RUN_TIME) {
                    // config root field is volatile; we initialize and read config from the readConfig method
                    cc.getFieldCreator(rootFieldDescriptor)
                            .setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE);
                    // construct instance in readConfig
                    final ResultHandle instance = readConfig.invokeStaticMethod(ctor);
                    // assign instance to field
                    readConfig.writeStaticField(rootFieldDescriptor, instance);
                    if (!rootName.isEmpty()) {
                        readConfig.invokeVirtualMethod(SB_APPEND_CHAR, readConfigNameBuilder, readConfig.load('.'));
                        readConfig.invokeVirtualMethod(SB_APPEND_STRING, readConfigNameBuilder,
                                readConfig.load(rootName));
                    }
                    readConfig.invokeStaticMethod(initGroup, runTimeConfig, readConfigNameBuilder, instance);
                    readConfig.invokeVirtualMethod(SB_SET_LENGTH, readConfigNameBuilder, rcOldLen);
                } else {
                    assert root.getConfigPhase() == ConfigPhase.BUILD_TIME;
                    // ignore explicitly for now (no eager read for these)
                }
            }

            ResultHandle nameSet;
            ResultHandle iterator;

            // generate sweep for clinit
            nameSet = clinit.invokeVirtualMethod(SRC_GET_PROPERTY_NAMES, clinitConfig);
            iterator = clinit.invokeInterfaceMethod(ITRA_ITERATOR, nameSet);

            try (BytecodeCreator sweepLoop = clinit.createScope()) {
                try (BytecodeCreator hasNext = sweepLoop.ifNonZero(sweepLoop.invokeInterfaceMethod(ITR_HAS_NEXT, iterator))
                        .trueBranch()) {

                    final ResultHandle key = hasNext.checkCast(hasNext.invokeInterfaceMethod(ITR_NEXT, iterator), String.class);
                    // NameIterator keyIter = new NameIterator(key);
                    final ResultHandle keyIter = hasNext.newInstance(NI_NEW_STRING, key);
                    // if (! keyIter.hasNext()) continue sweepLoop;
                    hasNext.ifNonZero(hasNext.invokeVirtualMethod(NI_HAS_NEXT, keyIter)).falseBranch().continueScope(sweepLoop);
                    // if (! keyIter.nextSegmentEquals("quarkus")) continue sweepLoop;
                    hasNext.ifNonZero(hasNext.invokeVirtualMethod(NI_NEXT_EQUALS, keyIter, hasNext.load("quarkus")))
                            .falseBranch().continueScope(sweepLoop);
                    // keyIter.next(); // skip "quarkus"
                    hasNext.invokeVirtualMethod(NI_NEXT, keyIter);
                    // parse(config, keyIter);
                    hasNext.invokeStaticMethod(siParserBody, clinitConfig, keyIter);
                    // continue sweepLoop;
                    hasNext.continueScope(sweepLoop);
                }
            }

            // generate sweep for run time
            nameSet = readConfig.invokeVirtualMethod(SRC_GET_PROPERTY_NAMES, runTimeConfig);
            iterator = readConfig.invokeInterfaceMethod(ITRA_ITERATOR, nameSet);

            try (BytecodeCreator sweepLoop = readConfig.createScope()) {
                try (BytecodeCreator hasNext = sweepLoop.ifNonZero(sweepLoop.invokeInterfaceMethod(ITR_HAS_NEXT, iterator))
                        .trueBranch()) {

                    final ResultHandle key = hasNext.checkCast(hasNext.invokeInterfaceMethod(ITR_NEXT, iterator), String.class);
                    // NameIterator keyIter = new NameIterator(key);
                    final ResultHandle keyIter = hasNext.newInstance(NI_NEW_STRING, key);
                    // if (! keyIter.hasNext()) continue sweepLoop;
                    hasNext.ifNonZero(hasNext.invokeVirtualMethod(NI_HAS_NEXT, keyIter)).falseBranch().continueScope(sweepLoop);
                    // if (! keyIter.nextSegmentEquals("quarkus")) continue sweepLoop;
                    hasNext.ifNonZero(hasNext.invokeVirtualMethod(NI_NEXT_EQUALS, keyIter, hasNext.load("quarkus")))
                            .falseBranch().continueScope(sweepLoop);
                    // keyIter.next(); // skip "quarkus"
                    hasNext.invokeVirtualMethod(NI_NEXT, keyIter);
                    // parse(config, keyIter);
                    hasNext.invokeStaticMethod(rtParserBody, runTimeConfig, keyIter);
                    // continue sweepLoop;
                    hasNext.continueScope(sweepLoop);
                }
            }

            // generate ensure-initialized method
            try (MethodCreator mc = cc.getMethodCreator(C_ENSURE_INITIALIZED)) {
                mc.setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                mc.returnValue(null);
            }

            // generate run time entry point
            try (MethodCreator mc = cc.getMethodCreator(C_CREATE_RUN_TIME_CONFIG)) {
                mc.setModifiers(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                ResultHandle instance = mc.newInstance(MethodDescriptor.ofConstructor(CONFIG_CLASS_NAME));
                mc.invokeVirtualMethod(C_READ_CONFIG, instance);
                mc.returnValue(null);
            }

            // wrap it up
            final BytecodeCreator isError = readConfig.ifNonZero(readConfig.invokeStaticMethod(CD_IS_ERROR)).trueBranch();
            ResultHandle niceErrorMessage = isError
                    .invokeStaticMethod(
                            MethodDescriptor.ofMethod(ConfigDiagnostic.class, "getNiceErrorMessage", String.class));
            readConfig.invokeStaticMethod(CD_RESET_ERROR);

            // throw the proper exception
            final ResultHandle finalErrorMessageBuilder = isError.newInstance(SB_NEW);
            isError.invokeVirtualMethod(SB_APPEND_STRING, finalErrorMessageBuilder, isError
                    .load("One or more configuration errors has prevented the application from starting. The errors are:\n"));
            isError.invokeVirtualMethod(SB_APPEND_STRING, finalErrorMessageBuilder, niceErrorMessage);
            final ResultHandle finalErrorMessage = isError.invokeVirtualMethod(OBJ_TO_STRING, finalErrorMessageBuilder);
            final ResultHandle configurationException = isError
                    .newInstance(MethodDescriptor.ofConstructor(ConfigurationException.class, String.class), finalErrorMessage);
            final ResultHandle emptyStackTraceElement = isError.newArray(StackTraceElement.class, isError.load(0));
            // empty out the stack trace in order to not make the configuration errors more visible (the stack trace contains generated classes anyway that don't provide any value)
            isError.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(ConfigurationException.class, "setStackTrace", void.class,
                            StackTraceElement[].class),
                    configurationException, emptyStackTraceElement);
            isError.throwException(configurationException);

            readConfig.returnValue(null);
            readConfig.close();
            clinit.returnValue(null);
            clinit.close();
            cc.close();

            // generate run time default values config source class
            try (ClassCreator dvcc = ClassCreator.builder().classOutput(classOutput).className(RTDVCS_CLASS_NAME)
                    .superClass(AbstractRawDefaultConfigSource.class).setFinal(true).build()) {
                // implements abstract method AbstractRawDefaultConfigSource#getValue(NameIterator)
                try (MethodCreator mc = dvcc.getMethodCreator("getValue", String.class, NameIterator.class)) {
                    final ResultHandle keyIter = mc.getMethodParam(0);
                    final MethodDescriptor md = generateDefaultValueParse(dvcc, runTimePatternMap,
                            new StringBuilder("getDefaultFor"));
                    if (md != null) {
                        // there is at least one default value
                        final BranchResult if1 = mc.ifNonZero(mc.invokeVirtualMethod(NI_HAS_NEXT, keyIter));
                        try (BytecodeCreator true1 = if1.trueBranch()) {
                            true1.invokeVirtualMethod(NI_NEXT, keyIter);
                            final BranchResult if2 = true1
                                    .ifNonZero(true1.invokeVirtualMethod(NI_PREVIOUS_EQUALS, keyIter, true1.load("quarkus")));
                            try (BytecodeCreator true2 = if2.trueBranch()) {
                                final ResultHandle result = true2.invokeVirtualMethod(
                                        md, mc.getThis(), keyIter);
                                true2.returnValue(result);
                            }
                        }
                    }

                    mc.returnValue(mc.loadNull());
                }
            }

            // generate build time run time visible default values config source class
            try (ClassCreator dvcc = ClassCreator.builder().classOutput(classOutput).className(BTRTDVCS_CLASS_NAME)
                    .superClass(AbstractRawDefaultConfigSource.class).setFinal(true).build()) {
                // implements abstract method AbstractRawDefaultConfigSource#getValue(NameIterator)
                try (MethodCreator mc = dvcc.getMethodCreator("getValue", String.class, NameIterator.class)) {
                    final ResultHandle keyIter = mc.getMethodParam(0);
                    final MethodDescriptor md = generateDefaultValueParse(dvcc, buildTimeRunTimePatternMap,
                            new StringBuilder("getDefaultFor"));
                    if (md != null) {
                        // there is at least one default value
                        final BranchResult if1 = mc.ifNonZero(mc.invokeVirtualMethod(NI_HAS_NEXT, keyIter));
                        try (BytecodeCreator true1 = if1.trueBranch()) {
                            true1.invokeVirtualMethod(NI_NEXT, keyIter);
                            final BranchResult if2 = true1
                                    .ifNonZero(true1.invokeVirtualMethod(NI_PREVIOUS_EQUALS, keyIter, true1.load("quarkus")));
                            try (BytecodeCreator true2 = if2.trueBranch()) {
                                final ResultHandle result = true2.invokeVirtualMethod(
                                        md, mc.getThis(), keyIter);
                                true2.returnValue(result);
                            }
                        }
                    }

                    mc.returnValue(mc.loadNull());
                }
            }
        }

        private MethodDescriptor generateInitGroup(ClassDefinition definition) {
            final Class<?> clazz = definition.getConfigurationClass();
            MethodDescriptor methodDescriptor = groupInitMethods.get(clazz);
            if (methodDescriptor != null) {
                return methodDescriptor;
            }
            methodDescriptor = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME, "initGroup$" + clazz.getName().replace('.', '$'),
                    void.class, SmallRyeConfig.class, StringBuilder.class, Object.class);
            final MethodCreator bc = cc.getMethodCreator(methodDescriptor).setModifiers(Opcodes.ACC_STATIC);
            final ResultHandle config = bc.getMethodParam(0);
            // on entry, nameBuilder is our name
            final ResultHandle nameBuilder = bc.getMethodParam(1);
            final ResultHandle instance = bc.getMethodParam(2);
            final ResultHandle length = bc.invokeVirtualMethod(SB_LENGTH, nameBuilder);
            for (ClassDefinition.ClassMember member : definition.getMembers()) {
                // common setup
                final String propertyName = member.getPropertyName();
                final MethodDescriptor setter = accessorFinder.getSetterFor(member.getDescriptor());
                if (!propertyName.isEmpty()) {
                    // append the property name
                    bc.invokeVirtualMethod(SB_APPEND_CHAR, nameBuilder, bc.load('.'));
                    bc.invokeVirtualMethod(SB_APPEND_STRING, nameBuilder, bc.load(propertyName));
                }
                if (member instanceof ClassDefinition.ItemMember) {
                    ClassDefinition.ItemMember leafMember = (ClassDefinition.ItemMember) member;
                    final FieldDescriptor convField = getOrCreateConverterInstance(leafMember.getField());
                    final ResultHandle name = bc.invokeVirtualMethod(OBJ_TO_STRING, nameBuilder);
                    final ResultHandle converter = bc.readStaticField(convField);
                    try (TryBlock tryBlock = bc.tryBlock()) {
                        final ResultHandle val = tryBlock.invokeVirtualMethod(SRC_GET_VALUE, config, name, converter);
                        tryBlock.invokeStaticMethod(setter, instance, val);
                        try (CatchBlockCreator catchBadValue = tryBlock.addCatch(IllegalArgumentException.class)) {
                            catchBadValue.invokeStaticMethod(CD_INVALID_VALUE, name, catchBadValue.getCaughtException());
                        }
                        try (CatchBlockCreator catchNoValue = tryBlock.addCatch(NoSuchElementException.class)) {
                            catchNoValue.invokeStaticMethod(CD_MISSING_VALUE, name, catchNoValue.getCaughtException());
                        }
                    }
                } else if (member instanceof ClassDefinition.GroupMember) {
                    ClassDefinition.GroupMember groupMember = (ClassDefinition.GroupMember) member;
                    if (groupMember.isOptional()) {
                        bc.invokeStaticMethod(setter, instance, bc.invokeStaticMethod(OPT_EMPTY));
                    } else {
                        final GroupDefinition groupDefinition = groupMember.getGroupDefinition();
                        final MethodDescriptor nested = generateInitGroup(groupDefinition);
                        final MethodDescriptor ctor = accessorFinder
                                .getConstructorFor(MethodDescriptor.ofConstructor(groupDefinition.getConfigurationClass()));
                        final ResultHandle nestedInstance = bc.invokeStaticMethod(ctor);
                        bc.invokeStaticMethod(nested, config, nameBuilder, nestedInstance);
                        bc.invokeStaticMethod(setter, instance, nestedInstance);
                    }
                } else {
                    assert member instanceof ClassDefinition.MapMember;
                    final ResultHandle map = bc.newInstance(TM_NEW);
                    bc.invokeStaticMethod(setter, instance, map);
                }
                if (!propertyName.isEmpty()) {
                    // restore length
                    bc.invokeVirtualMethod(SB_SET_LENGTH, nameBuilder, length);
                }
            }
            bc.returnValue(null);
            groupInitMethods.put(clazz, methodDescriptor);
            return methodDescriptor;
        }

        private static MethodDescriptor generateDefaultValueParse(final ClassCreator dvcc,
                final ConfigPatternMap<Container> keyMap, final StringBuilder methodName) {

            final Container matched = keyMap.getMatched();
            final boolean hasDefault;
            if (matched != null) {
                final ClassDefinition.ClassMember member = matched.getClassMember();
                // matched members *must* be item members
                assert member instanceof ClassDefinition.ItemMember;
                ClassDefinition.ItemMember itemMember = (ClassDefinition.ItemMember) member;
                hasDefault = itemMember.getDefaultValue() != null;
            } else {
                hasDefault = false;
            }

            final Iterable<String> names = keyMap.childNames();
            final Map<String, MethodDescriptor> children = new HashMap<>();
            MethodDescriptor wildCard = null;
            for (String name : names) {
                final int length = methodName.length();
                if (name.equals(ConfigPatternMap.WILD_CARD)) {
                    methodName.append(":*");
                    wildCard = generateDefaultValueParse(dvcc, keyMap.getChild(ConfigPatternMap.WILD_CARD), methodName);
                } else {
                    methodName.append(':').append(name);
                    final MethodDescriptor value = generateDefaultValueParse(dvcc, keyMap.getChild(name), methodName);
                    if (value != null) {
                        children.put(name, value);
                    }
                }
                methodName.setLength(length);
            }
            if (children.isEmpty() && wildCard == null && !hasDefault) {
                // skip parse trees with no default values in them
                return null;
            }

            try (MethodCreator body = dvcc.getMethodCreator(methodName.toString(), String.class, NameIterator.class)) {
                body.setModifiers(Opcodes.ACC_PRIVATE);

                final ResultHandle keyIter = body.getMethodParam(0);
                // if we've matched the whole thing...
                // if (! keyIter.hasNext()) {
                try (BytecodeCreator matchedBody = body.ifNonZero(body.invokeVirtualMethod(NI_HAS_NEXT, keyIter))
                        .falseBranch()) {
                    if (matched != null) {
                        final ClassDefinition.ClassMember member = matched.getClassMember();
                        // matched members *must* be item members
                        assert member instanceof ClassDefinition.ItemMember;
                        ClassDefinition.ItemMember itemMember = (ClassDefinition.ItemMember) member;
                        // match?
                        final String defaultValue = itemMember.getDefaultValue();
                        if (defaultValue != null) {
                            // matched with default value
                            // return "defaultValue";
                            matchedBody.returnValue(matchedBody.load(defaultValue));
                        } else {
                            // matched but no default value
                            // return null;
                            matchedBody.returnValue(matchedBody.loadNull());
                        }
                    } else {
                        // no match
                        // return null;
                        matchedBody.returnValue(matchedBody.loadNull());
                    }
                }
                // }
                // branches for each next-string
                for (String name : children.keySet()) {
                    // TODO: string switch
                    // if (keyIter.nextSegmentEquals(name)) {
                    try (BytecodeCreator nameMatched = body
                            .ifNonZero(body.invokeVirtualMethod(NI_NEXT_EQUALS, keyIter, body.load(name))).trueBranch()) {
                        // keyIter.next();
                        nameMatched.invokeVirtualMethod(NI_NEXT, keyIter);
                        // (generated recursive)
                        // result = getDefault$..$name(keyIter);
                        ResultHandle result = nameMatched.invokeVirtualMethod(children.get(name), body.getThis(), keyIter);
                        // return result;
                        nameMatched.returnValue(result);
                    }
                    // }
                }
                if (wildCard != null) {
                    // consume and parse
                    try (BytecodeCreator matchedBody = body.ifNonZero(body.invokeVirtualMethod(NI_HAS_NEXT, keyIter))
                            .trueBranch()) {
                        // keyIter.next();
                        matchedBody.invokeVirtualMethod(NI_NEXT, keyIter);
                        // (generated recursive)
                        // result = getDefault$..$*(keyIter);
                        final ResultHandle result = matchedBody.invokeVirtualMethod(wildCard, body.getThis(), keyIter);
                        // return result;
                        matchedBody.returnValue(result);
                    }
                }
                // unknown
                // return null;
                body.returnValue(body.loadNull());

                return body.getMethodDescriptor();
            }
        }

        private MethodDescriptor generateParserBody(final ConfigPatternMap<Container> keyMap,
                final ConfigPatternMap<?> ignoredMap, final StringBuilder methodName, final boolean dynamic,
                final boolean isRunTime) {
            try (MethodCreator body = cc.getMethodCreator(methodName.toString(), void.class,
                    SmallRyeConfig.class, NameIterator.class)) {
                body.setModifiers(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
                final ResultHandle config = body.getMethodParam(0);
                final ResultHandle keyIter = body.getMethodParam(1);
                final Container matched = keyMap == null ? null : keyMap.getMatched();
                final Object ignoreMatched = ignoredMap == null ? null : ignoredMap.getMatched();
                // if (! keyIter.hasNext()) {
                try (BytecodeCreator matchedBody = body.ifNonZero(body.invokeVirtualMethod(NI_HAS_NEXT, keyIter))
                        .falseBranch()) {
                    if (matched != null) {
                        final ClassDefinition.ClassMember member = matched.getClassMember();
                        // matched members *must* be item members
                        assert member instanceof ClassDefinition.ItemMember;
                        ClassDefinition.ItemMember itemMember = (ClassDefinition.ItemMember) member;

                        if (matched instanceof FieldContainer) {
                            final FieldContainer fieldContainer = (FieldContainer) matched;
                            if (dynamic) {
                                if (!itemMember.getPropertyName().isEmpty()) {
                                    // consume segment
                                    matchedBody.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                                }
                                // we have to get or create all containing (and contained) groups of this member
                                matchedBody.invokeStaticMethod(generateGetEnclosing(fieldContainer, isRunTime), keyIter,
                                        config);
                            }
                            // else ignore (already populated eagerly)
                        } else {
                            assert matched instanceof MapContainer;
                            MapContainer mapContainer = (MapContainer) matched;
                            // map leafs are always dynamic
                            final ResultHandle lastSeg = matchedBody.invokeVirtualMethod(NI_GET_PREVIOUS_SEGMENT, keyIter);
                            matchedBody.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                            final ResultHandle mapHandle = matchedBody
                                    .invokeStaticMethod(generateGetEnclosing(mapContainer, isRunTime), keyIter, config);
                            // populate the map
                            final Field field = mapContainer.findField();
                            final FieldDescriptor fd = getOrCreateConverterInstance(field);
                            final ResultHandle key = matchedBody.invokeVirtualMethod(NI_GET_NAME, keyIter);
                            final ResultHandle converter = matchedBody.readStaticField(fd);
                            final ResultHandle value = matchedBody.invokeVirtualMethod(SRC_GET_VALUE, config, key, converter);
                            matchedBody.invokeInterfaceMethod(MAP_PUT, mapHandle, lastSeg, value);
                        }
                    } else if (ignoreMatched == null) {
                        // name is unknown
                        matchedBody.invokeStaticMethod(isRunTime ? CD_UNKNOWN_RT : CD_UNKNOWN, keyIter);
                    }
                    // return;
                    matchedBody.returnValue(null);
                }
                // }
                boolean hasWildCard = false;
                // branches for each next-string
                if (keyMap != null) {
                    final Iterable<String> names = keyMap.childNames();
                    for (String name : names) {
                        if (name.equals(ConfigPatternMap.WILD_CARD)) {
                            hasWildCard = true;
                        } else {
                            // TODO: string switch
                            // if (keyIter.nextSegmentEquals(name)) {
                            try (BytecodeCreator nameMatched = body
                                    .ifNonZero(body.invokeVirtualMethod(NI_NEXT_EQUALS, keyIter, body.load(name)))
                                    .trueBranch()) {
                                // keyIter.next();
                                nameMatched.invokeVirtualMethod(NI_NEXT, keyIter);
                                // (generated recursive)
                                final int length = methodName.length();
                                methodName.append(':').append(name);
                                nameMatched.invokeStaticMethod(
                                        generateParserBody(keyMap.getChild(name),
                                                ignoredMap == null ? null : ignoredMap.getChild(name), methodName, dynamic,
                                                isRunTime),
                                        config, keyIter);
                                methodName.setLength(length);
                                // return;
                                nameMatched.returnValue(null);
                            }
                            // }
                        }
                    }
                }
                // branches for each ignored child
                if (ignoredMap != null) {
                    final Iterable<String> names = ignoredMap.childNames();
                    for (String name : names) {
                        if (name.equals(ConfigPatternMap.WILD_CARD)) {
                            hasWildCard = true;
                        } else {
                            final ConfigPatternMap<Container> keyChildMap = keyMap == null ? null : keyMap.getChild(name);
                            if (keyChildMap != null) {
                                // we already did this one
                                continue;
                            }
                            // TODO: string switch
                            // if (keyIter.nextSegmentEquals(name)) {
                            try (BytecodeCreator nameMatched = body
                                    .ifNonZero(body.invokeVirtualMethod(NI_NEXT_EQUALS, keyIter, body.load(name)))
                                    .trueBranch()) {
                                // keyIter.next();
                                nameMatched.invokeVirtualMethod(NI_NEXT, keyIter);
                                // (generated recursive)
                                final int length = methodName.length();
                                methodName.append(':').append(name);
                                nameMatched.invokeStaticMethod(
                                        generateParserBody(null, ignoredMap.getChild(name), methodName, false, isRunTime),
                                        config, keyIter);
                                methodName.setLength(length);
                                // return;
                                nameMatched.returnValue(null);
                            }
                            // }
                        }
                    }
                }
                if (hasWildCard) {
                    assert keyMap != null || ignoredMap != null;
                    // consume and parse
                    try (BytecodeCreator matchedBody = body.ifNonZero(body.invokeVirtualMethod(NI_HAS_NEXT, keyIter))
                            .trueBranch()) {
                        // keyIter.next();
                        matchedBody.invokeVirtualMethod(NI_NEXT, keyIter);
                        // (generated recursive)
                        final int length = methodName.length();
                        methodName.append(":*");
                        matchedBody.invokeStaticMethod(
                                generateParserBody(keyMap == null ? null : keyMap.getChild(ConfigPatternMap.WILD_CARD),
                                        ignoredMap == null ? null : ignoredMap.getChild(ConfigPatternMap.WILD_CARD),
                                        methodName,
                                        true, isRunTime),
                                config, keyIter);
                        methodName.setLength(length);
                        // return;
                        matchedBody.returnValue(null);
                    }
                }
                body.invokeStaticMethod(isRunTime ? CD_UNKNOWN_RT : CD_UNKNOWN, keyIter);
                body.returnValue(null);
                return body.getMethodDescriptor();
            }
        }

        private MethodDescriptor generateGetEnclosing(final FieldContainer matchNode, final boolean isRunTime) {
            // name iterator cursor is placed BEFORE the field name on entry
            MethodDescriptor md = enclosingMemberMethods.get(matchNode);
            if (md != null) {
                return md;
            }
            md = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME,
                    (isRunTime ? "rt" : "si") + "GetEnclosing:" + matchNode.getCombinedName(), Object.class,
                    NameIterator.class, SmallRyeConfig.class);
            try (MethodCreator mc = cc.getMethodCreator(md)) {
                mc.setModifiers(Opcodes.ACC_STATIC);
                final ResultHandle keyIter = mc.getMethodParam(0);
                final ResultHandle config = mc.getMethodParam(1);
                final ClassDefinition.ClassMember member = matchNode.getClassMember();
                final Container parent = matchNode.getParent();
                if (parent == null) {
                    // it's a root
                    final RootDefinition definition = (RootDefinition) member.getEnclosingDefinition();
                    FieldDescriptor fieldDescriptor = configRootsByType.get(definition.getConfigurationClass());
                    assert fieldDescriptor != null : "Field descriptor defined for " + definition.getConfigurationClass();
                    mc.returnValue(mc.readStaticField(fieldDescriptor));
                } else if (parent instanceof FieldContainer) {
                    // get the parent
                    final FieldContainer fieldContainer = (FieldContainer) parent;
                    final ClassDefinition.ClassMember classMember = fieldContainer.getClassMember();
                    if (!classMember.getPropertyName().isEmpty()) {
                        // consume segment
                        mc.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                    }
                    final ResultHandle enclosing = mc.invokeStaticMethod(generateGetEnclosing(fieldContainer, isRunTime),
                            keyIter, config);
                    final MethodDescriptor getter = accessorFinder.getGetterFor(classMember.getDescriptor());
                    final ResultHandle fieldVal = mc.invokeStaticMethod(getter, enclosing);
                    final AssignableResultHandle group = mc.createVariable(Object.class);
                    if (classMember instanceof ClassDefinition.GroupMember
                            && ((ClassDefinition.GroupMember) classMember).isOptional()) {
                        final BranchResult isPresent = mc.ifNonZero(mc.invokeVirtualMethod(OPT_IS_PRESENT, fieldVal));
                        final BytecodeCreator trueBranch = isPresent.trueBranch();
                        final BytecodeCreator falseBranch = isPresent.falseBranch();
                        // it already exists
                        trueBranch.assign(group, trueBranch.invokeVirtualMethod(OPT_GET, fieldVal));
                        // it doesn't exist, recreate it
                        final MethodDescriptor ctor = accessorFinder.getConstructorFor(
                                MethodDescriptor.ofConstructor(member.getEnclosingDefinition().getConfigurationClass()));
                        final ResultHandle instance = falseBranch.invokeStaticMethod(ctor);
                        final ResultHandle precedingKey = falseBranch.invokeVirtualMethod(NI_GET_ALL_PREVIOUS_SEGMENTS,
                                keyIter);
                        final ResultHandle nameBuilder = falseBranch.newInstance(SB_NEW_STR, precedingKey);
                        falseBranch.invokeStaticMethod(generateInitGroup(member.getEnclosingDefinition()), config, nameBuilder,
                                instance);
                        final MethodDescriptor setter = accessorFinder.getSetterFor(classMember.getDescriptor());
                        falseBranch.invokeStaticMethod(setter, fieldVal, falseBranch.invokeStaticMethod(OPT_OF, instance));
                        falseBranch.assign(group, instance);
                    } else {
                        mc.assign(group, fieldVal);
                    }
                    if (!classMember.getPropertyName().isEmpty()) {
                        // restore
                        mc.invokeVirtualMethod(NI_NEXT, keyIter);
                    }
                    mc.returnValue(group);
                } else {
                    assert parent instanceof MapContainer;
                    // the map might or might not contain this group
                    final MapContainer mapContainer = (MapContainer) parent;
                    final ResultHandle key = mc.invokeVirtualMethod(NI_GET_PREVIOUS_SEGMENT, keyIter);
                    // consume segment
                    mc.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                    final ResultHandle map = mc.invokeStaticMethod(generateGetEnclosing(mapContainer, isRunTime), keyIter,
                            config);
                    // restore
                    mc.invokeVirtualMethod(NI_NEXT, keyIter);
                    final ResultHandle existing = mc.invokeInterfaceMethod(MAP_GET, map, key);
                    mc.ifNull(existing).falseBranch().returnValue(existing);
                    // add the map key and initialize the enclosed item
                    final MethodDescriptor ctor = accessorFinder.getConstructorFor(
                            MethodDescriptor.ofConstructor(member.getEnclosingDefinition().getConfigurationClass()));
                    final ResultHandle instance = mc.invokeStaticMethod(ctor);
                    final ResultHandle precedingKey = mc.invokeVirtualMethod(NI_GET_ALL_PREVIOUS_SEGMENTS, keyIter);
                    final ResultHandle nameBuilder = mc.newInstance(SB_NEW_STR, precedingKey);
                    mc.invokeStaticMethod(generateInitGroup(member.getEnclosingDefinition()), config, nameBuilder, instance);
                    mc.invokeInterfaceMethod(MAP_PUT, map, key, instance);
                    mc.returnValue(instance);
                }
            }
            enclosingMemberMethods.put(matchNode, md);
            return md;
        }

        private MethodDescriptor generateGetEnclosing(final MapContainer matchNode, final boolean isRunTime) {
            // name iterator cursor is placed BEFORE the map key on entry
            MethodDescriptor md = enclosingMemberMethods.get(matchNode);
            if (md != null) {
                return md;
            }
            md = MethodDescriptor.ofMethod(CONFIG_CLASS_NAME,
                    (isRunTime ? "rt" : "si") + "GetEnclosing:" + matchNode.getCombinedName(), Object.class,
                    NameIterator.class, SmallRyeConfig.class);
            try (MethodCreator mc = cc.getMethodCreator(md)) {
                mc.setModifiers(Opcodes.ACC_STATIC);
                final ResultHandle keyIter = mc.getMethodParam(0);
                final ResultHandle config = mc.getMethodParam(1);
                final Container parent = matchNode.getParent();
                if (parent instanceof FieldContainer) {
                    // get the parent
                    final FieldContainer fieldContainer = (FieldContainer) parent;
                    if (!fieldContainer.getClassMember().getPropertyName().isEmpty()) {
                        // consume segment
                        mc.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                    }
                    final ResultHandle enclosing = mc.invokeStaticMethod(generateGetEnclosing(fieldContainer, isRunTime),
                            keyIter, config);
                    if (!fieldContainer.getClassMember().getPropertyName().isEmpty()) {
                        // restore
                        mc.invokeVirtualMethod(NI_NEXT, keyIter);
                    }
                    final MethodDescriptor getter = accessorFinder
                            .getGetterFor(fieldContainer.getClassMember().getDescriptor());
                    mc.returnValue(mc.invokeStaticMethod(getter, enclosing));
                } else {
                    assert parent instanceof MapContainer;
                    // the map might or might not contain this map
                    final MapContainer mapContainer = (MapContainer) parent;
                    final ResultHandle key = mc.invokeVirtualMethod(NI_GET_PREVIOUS_SEGMENT, keyIter);
                    // consume enclosing map key
                    mc.invokeVirtualMethod(NI_PREVIOUS, keyIter);
                    final ResultHandle map = mc.invokeStaticMethod(generateGetEnclosing(mapContainer, isRunTime), keyIter,
                            config);
                    // restore
                    mc.invokeVirtualMethod(NI_NEXT, keyIter);
                    final ResultHandle existing = mc.invokeInterfaceMethod(MAP_GET, map, key);
                    mc.ifNull(existing).falseBranch().returnValue(existing);
                    // add the map key and initialize the enclosed item
                    final ResultHandle instance = mc.newInstance(TM_NEW);
                    mc.invokeInterfaceMethod(MAP_PUT, map, key, instance);
                    mc.returnValue(instance);
                }
            }
            enclosingMemberMethods.put(matchNode, md);
            return md;
        }

        private FieldDescriptor getOrCreateConverterInstance(Field field) {
            return getOrCreateConverterInstance(field, ConverterType.of(field));
        }

        private FieldDescriptor getOrCreateConverterInstance(Field field, ConverterType type) {
            FieldDescriptor fd = convertersByType.get(type);
            if (fd != null) {
                return fd;
            }

            fd = FieldDescriptor.of(cc.getClassName(), "conv$" + converterIndex++, Converter.class);
            ResultHandle converter;
            boolean storeConverter = false;
            if (type instanceof Leaf) {
                // simple type
                final Leaf leaf = (Leaf) type;
                final Class<? extends Converter<?>> convertWith = leaf.getConvertWith();
                if (convertWith != null) {
                    // TODO: temporary until type param inference is in
                    if (convertWith == HyphenateEnumConverter.class.asSubclass(Converter.class)) {
                        converter = converterSetup.newInstance(MethodDescriptor.ofConstructor(convertWith, Class.class),
                                converterSetup.loadClass(type.getLeafType()));
                    } else {
                        converter = converterSetup.newInstance(MethodDescriptor.ofConstructor(convertWith));
                    }
                } else {
                    final ResultHandle clazzHandle = converterSetup.loadClass(leaf.getLeafType());
                    converter = converterSetup.invokeVirtualMethod(SRC_GET_CONVERTER, clinitConfig, clazzHandle);
                    storeConverter = true;
                }
            } else if (type instanceof ArrayOf) {
                final ArrayOf arrayOf = (ArrayOf) type;
                final ResultHandle nestedConv = instanceCache
                        .get(getOrCreateConverterInstance(field, arrayOf.getElementType()));
                converter = converterSetup.invokeStaticMethod(CONVS_NEW_ARRAY_CONVERTER, nestedConv,
                        converterSetup.loadClass(arrayOf.getArrayType()));
            } else if (type instanceof CollectionOf) {
                final CollectionOf collectionOf = (CollectionOf) type;
                final ResultHandle nestedConv = instanceCache
                        .get(getOrCreateConverterInstance(field, collectionOf.getElementType()));
                final ResultHandle factory;
                final Class<?> collectionClass = collectionOf.getCollectionClass();
                if (collectionClass == List.class) {
                    factory = converterSetup.invokeStaticMethod(CU_LIST_FACTORY);
                } else if (collectionClass == Set.class) {
                    factory = converterSetup.invokeStaticMethod(CU_SET_FACTORY);
                } else if (collectionClass == SortedSet.class) {
                    factory = converterSetup.invokeStaticMethod(CU_SORTED_SET_FACTORY);
                } else {
                    throw reportError(field, "Unsupported configuration collection type: %s", collectionClass);
                }
                converter = converterSetup.invokeStaticMethod(CONVS_NEW_COLLECTION_CONVERTER, nestedConv, factory);
            } else if (type instanceof LowerBoundCheckOf) {
                final LowerBoundCheckOf boundCheckOf = (LowerBoundCheckOf) type;
                // todo: add in bounds checker
                converter = instanceCache
                        .get(getOrCreateConverterInstance(field, boundCheckOf.getClassConverterType()));
            } else if (type instanceof UpperBoundCheckOf) {
                final UpperBoundCheckOf boundCheckOf = (UpperBoundCheckOf) type;
                // todo: add in bounds checker
                converter = instanceCache
                        .get(getOrCreateConverterInstance(field, boundCheckOf.getClassConverterType()));
            } else if (type instanceof MinMaxValidated) {
                MinMaxValidated minMaxValidated = (MinMaxValidated) type;
                String min = minMaxValidated.getMin();
                boolean minInclusive = minMaxValidated.isMinInclusive();
                String max = minMaxValidated.getMax();
                boolean maxInclusive = minMaxValidated.isMaxInclusive();
                final ResultHandle nestedConv = instanceCache
                        .get(getOrCreateConverterInstance(field, minMaxValidated.getNestedType()));
                if (min != null) {
                    if (max != null) {
                        converter = converterSetup.invokeStaticMethod(
                                CONVS_RANGE_VALUE_STRING_CONVERTER,
                                nestedConv,
                                converterSetup.load(min),
                                converterSetup.load(minInclusive),
                                converterSetup.load(max),
                                converterSetup.load(maxInclusive));
                    } else {
                        converter = converterSetup.invokeStaticMethod(
                                CONVS_MINIMUM_VALUE_STRING_CONVERTER,
                                nestedConv,
                                converterSetup.load(min),
                                converterSetup.load(minInclusive));
                    }
                } else {
                    assert min == null && max != null;
                    converter = converterSetup.invokeStaticMethod(
                            CONVS_MAXIMUM_VALUE_STRING_CONVERTER,
                            nestedConv,
                            converterSetup.load(max),
                            converterSetup.load(maxInclusive));
                }
            } else if (type instanceof OptionalOf) {
                OptionalOf optionalOf = (OptionalOf) type;
                final ResultHandle nestedConv = instanceCache
                        .get(getOrCreateConverterInstance(field, optionalOf.getNestedType()));
                converter = converterSetup.invokeStaticMethod(CONVS_NEW_OPTIONAL_CONVERTER, nestedConv);
            } else if (type instanceof PatternValidated) {
                PatternValidated patternValidated = (PatternValidated) type;
                final ResultHandle nestedConv = instanceCache
                        .get(getOrCreateConverterInstance(field, patternValidated.getNestedType()));
                final ResultHandle patternStr = converterSetup.load(patternValidated.getPatternString());
                converter = converterSetup.invokeStaticMethod(CONVS_PATTERN_CONVERTER, nestedConv, patternStr);
            } else {
                throw Assert.unreachableCode();
            }
            cc.getFieldCreator(fd).setModifiers(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
            converterSetup.writeStaticField(fd, converter);
            convertersByType.put(type, fd);
            instanceCache.put(fd, converter);
            if (storeConverter) {
                convertersToRegister.put(fd, type.getLeafType());
            }
            return fd;
        }

        public void close() {
            try {
                clinit.close();
            } catch (Throwable t) {
                try {
                    cc.close();
                } catch (Throwable t2) {
                    t2.addSuppressed(t);
                    throw t2;
                }
                throw t;
            }
            cc.close();
        }

        static final class Builder {
            private ClassOutput classOutput;
            private BuildTimeConfigurationReader.ReadResult buildTimeReadResult;
            private Map<String, String> runTimeDefaults;
            private List<Class<?>> additionalTypes;

            Builder() {
            }

            ClassOutput getClassOutput() {
                return classOutput;
            }

            Builder setClassOutput(final ClassOutput classOutput) {
                this.classOutput = classOutput;
                return this;
            }

            BuildTimeConfigurationReader.ReadResult getBuildTimeReadResult() {
                return buildTimeReadResult;
            }

            Builder setBuildTimeReadResult(final BuildTimeConfigurationReader.ReadResult buildTimeReadResult) {
                this.buildTimeReadResult = buildTimeReadResult;
                return this;
            }

            Map<String, String> getRunTimeDefaults() {
                return runTimeDefaults;
            }

            Builder setRunTimeDefaults(final Map<String, String> runTimeDefaults) {
                this.runTimeDefaults = runTimeDefaults;
                return this;
            }

            List<Class<?>> getAdditionalTypes() {
                return additionalTypes;
            }

            Builder setAdditionalTypes(final List<Class<?>> additionalTypes) {
                this.additionalTypes = additionalTypes;
                return this;
            }

            GenerateOperation build() {
                return new GenerateOperation(this);
            }
        }
    }
}
