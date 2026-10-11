package sibarum.intrabuf.processor;

import sibarum.intrabuf.Header;
import sibarum.intrabuf.SchemaInfo;
import sibarum.intrabuf.annotation.IntrabufBundle;
import sibarum.intrabuf.annotation.IntrabufHeader;
import sibarum.intrabuf.annotation.IntrabufMessage;
import sibarum.intrabuf.schema.HeaderDef;
import sibarum.intrabuf.schema.EnumDef;
import sibarum.intrabuf.schema.Manifest;
import sibarum.intrabuf.schema.ManifestJson;
import sibarum.intrabuf.schema.MessageDef;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Generates a codec for every {@link IntrabufMessage}, {@link IntrabufHeader} and {@link IntrabufBundle} record, and a
 * manifest of every message and header schema the compilation can write.
 *
 * <p>Options ({@code -A<key>=<value>}):
 * <ul>
 *   <li>{@code intrabuf.buildVersion}: the build's version, recorded in every payload's schema info and in the
 *       manifest. A version ending in {@code -SNAPSHOT} makes this a snapshot build.</li>
 *   <li>{@code intrabuf.snapshot}: {@code true}/{@code false}, overriding what the version implies.</li>
 *   <li>{@code intrabuf.module}: the manifest's name, {@code META-INF/intrabuf/<module>.json}. Set it to something
 *       unique per artifact so manifests from several jars can sit on one classpath.</li>
 * </ul>
 * Without either version option the build is treated as a snapshot: payloads then carry a fingerprint and are
 * rejected by any reader whose schema differs, which is the safe failure.
 */
public final class IntrabufProcessor extends AbstractProcessor {

    static final String OPT_BUILD_VERSION = "intrabuf.buildVersion";
    static final String OPT_SNAPSHOT = "intrabuf.snapshot";
    static final String OPT_MODULE = "intrabuf.module";

    private Models models;
    private String buildVersion;
    private boolean snapshot;
    private String module;
    private final List<MessageModel> generated = new ArrayList<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(IntrabufMessage.class.getCanonicalName(), IntrabufHeader.class.getCanonicalName(),
                IntrabufBundle.class.getCanonicalName());
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OPT_BUILD_VERSION, OPT_SNAPSHOT, OPT_MODULE);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        models = new Models(env);
        buildVersion = env.getOptions().getOrDefault(OPT_BUILD_VERSION, "unversioned");
        String snap = env.getOptions().get(OPT_SNAPSHOT);
        snapshot = snap != null ? Boolean.parseBoolean(snap)
                : !env.getOptions().containsKey(OPT_BUILD_VERSION) || buildVersion.endsWith("-SNAPSHOT");
        module = env.getOptions().getOrDefault(OPT_MODULE, "intrabuf");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            if (!generated.isEmpty() && !models.hasErrors()) {
                writeManifest();
            }
            return false;
        }
        List<TypeElement> records = new ArrayList<>();
        for (Element e : round.getElementsAnnotatedWithAny(Set.of(IntrabufMessage.class, IntrabufHeader.class, IntrabufBundle.class))) {
            if (e instanceof TypeElement te) {
                records.add(te);
                models.markLocal(te);
            }
        }
        if (records.isEmpty()) {
            return false;
        }
        if (!processingEnv.getOptions().containsKey(OPT_BUILD_VERSION) && !processingEnv.getOptions().containsKey(OPT_SNAPSHOT)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "intrabuf: -A" + OPT_BUILD_VERSION
                    + " is not set; treating this build as a snapshot");
        }
        List<MessageModel> built = new ArrayList<>();
        for (TypeElement te : records) {
            MessageModel m = models.build(te, false);
            if (m != null) {
                built.add(m);
            }
        }
        if (models.hasErrors() || !models.link()) {
            return true;
        }
        for (MessageModel m : built) {
            write(m);
            generated.add(m);
        }
        return true;
    }

    private void write(MessageModel m) {
        String source = m.bundle() ? new BundleGenerator(m, models.fingerprint(m), snapshot, buildVersion).generate()
                : new CodecGenerator(m, models.fingerprint(m), snapshot, buildVersion).generate();
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(m.codecName(), m.element());
            try (Writer w = file.openWriter()) {
                w.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "intrabuf: cannot write " + m.codecName() + ": " + e.getMessage(), m.element());
        }
    }

    // ---- manifest ----------------------------------------------------------------------------------------------

    private void writeManifest() {
        List<MessageDef> messages = new ArrayList<>();
        List<HeaderDef> headers = new ArrayList<>();
        for (MessageModel m : models.all().values()) {
            if (m.bundle()) {
                continue; // not in the manifest yet: nothing reads a bundle from one
            }
            if (!m.header()) {
                messages.add(new MessageDef(info(m), m.defs()));
            } else if (!m.external()) {
                headers.add(new HeaderDef(new MessageDef(info(m), m.defs()), m.roles()));
            }
        }
        headers.sort(Comparator.comparing(HeaderDef::name));
        messages.sort(Comparator.comparing(MessageDef::name).thenComparingInt(MessageDef::major)
                .thenComparingInt(MessageDef::minor).thenComparing(d -> d.info().javaType()));
        List<EnumDef> enums = new ArrayList<>(models.enums().values());
        enums.sort(Comparator.comparing(EnumDef::name));
        Manifest manifest = new Manifest(Manifest.FORMAT, Header.WIRE_VERSION, module, buildVersion, snapshot, messages, enums,
                headers);
        String path = Manifest.RESOURCE_DIR + module + ".json";
        try {
            FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path,
                    generated.stream().map(MessageModel::element).toArray(Element[]::new));
            try (Writer w = file.openWriter()) {
                w.write(ManifestJson.write(manifest));
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "intrabuf: cannot write " + path + ": " + e.getMessage());
        }
    }

    /**
     * Schema info for the manifest. A local message is from this build; an external one is from whatever build
     * generated its codec, which recorded that in the codec's constants.
     */
    private SchemaInfo info(MessageModel m) {
        int fp = models.fingerprint(m);
        boolean snap = snapshot;
        String version = buildVersion;
        if (m.external()) {
            TypeElement codec = processingEnv.getElementUtils().getTypeElement(m.codecName());
            if (codec == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "intrabuf: " + m.javaName()
                        + " comes from another module but its codec " + m.codecName()
                        + " is not on the classpath; was that module compiled with the intrabuf processor?");
            } else {
                Object s = constant(codec, "SNAPSHOT");
                Object v = constant(codec, "BUILD_VERSION");
                Object f = constant(codec, "FINGERPRINT");
                snap = s instanceof Boolean b ? b : snap;
                version = v instanceof String str ? str : version;
                if (f instanceof Integer cf && cf != fp) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "intrabuf: " + m.javaName()
                            + " has fingerprint " + sibarum.intrabuf.Hex.int32(fp) + " here but its codec was generated with "
                            + sibarum.intrabuf.Hex.int32(cf) + "; the two modules disagree about its schema");
                }
            }
        }
        return new SchemaInfo(m.schemaName(), m.javaName(), m.id(), m.major(), m.minor(),
                m.compat(), fp, snap, version);
    }

    private static Object constant(TypeElement type, String name) {
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(name)) {
                return ((VariableElement) e).getConstantValue();
            }
        }
        return null;
    }
}
