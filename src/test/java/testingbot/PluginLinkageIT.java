package testingbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Guards the plugin's runtime linkage — the class of defect that a green {@code mvn test} cannot see.
 *
 * <p><b>Why this test exists.</b> Several third-party libraries are deliberately excluded from the
 * bundled {@code testingbotrest} jar in {@code pom.xml}, because Jenkins already ships those classes
 * through an {@code *-api} plugin and bundling a second copy causes conflicts. The catch is that a
 * Jenkins plugin classloader only sees plugins listed in its own {@code Plugin-Dependencies} manifest
 * entry, whereas Maven's flat test classpath sees every transitive jar. So an excluded library that is
 * not backed by a <em>direct</em> plugin dependency compiles, unit-tests and packages perfectly, then
 * throws {@code NoClassDefFoundError} on a real Jenkins. That is exactly how
 * {@code NoClassDefFoundError: org/json/JSONException} reached production.</p>
 *
 * <p>The usual safety net — a {@code JenkinsRule} test, which would load the plugin under a real
 * plugin classloader — is unavailable here: the {@code TestingBotTunnel} uber-jar bundles an old Jetty
 * that clashes with the test harness' Jetty 12, which is why {@code InjectedTest} is excluded in
 * {@code pom.xml}. This test closes that gap from the other side, by inspecting the packaged {@code .hpi}.</p>
 *
 * <p>It runs as a failsafe integration test because it needs the artifact produced by {@code package}.</p>
 *
 * <p><b>When this test fails</b> with an unmapped package, do not simply add it to
 * {@link #PACKAGE_PROVIDERS}. Decide first: either bundle the library (remove the exclusion) or declare
 * the Jenkins plugin that provides it as a direct dependency, then record that decision here.</p>
 */
public class PluginLinkageIT {

    /**
     * Packages that the bundled jars need but do not contain, mapped to the Jenkins plugin expected to
     * provide them. Keys are package prefixes; the longest match wins.
     */
    private static final Map<String, String> PACKAGE_PROVIDERS = new LinkedHashMap<>();

    static {
        // org.json:json is excluded from testingbotrest so we don't ship a second copy of classes
        // Jenkins already has. The json-api plugin provides them.
        PACKAGE_PROVIDERS.put("org.json", "json-api");
        // httpcore and httpmime are excluded from testingbotrest. Only httpclient is bundled, so the
        // core packages (org.apache.http, .entity, .message, .util, .entity.mime) come from the plugin.
        PACKAGE_PROVIDERS.put("org.apache.http", "apache-httpcomponents-client-4-api");
    }

    /** Bundled jars whose constant pools are scanned: those whose transitives the POM prunes. */
    private static final Pattern SCANNED_LIBS = Pattern.compile("WEB-INF/lib/testingbotrest-.*\\.jar");

    private static final Pattern INTERNAL_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([^;<>\\[]+);");

    /** Every class the .hpi ships, dotted. */
    private static final Set<String> shipped = new TreeSet<>();
    /** Every class the scanned libs reference, dotted. */
    private static final Set<String> referenced = new TreeSet<>();
    /** Declared plugin dependencies: short name -> whether it is optional. */
    private static final Map<String, Boolean> pluginDependencies = new TreeMap<>();

    @BeforeClass
    public static void readPackagedPlugin() throws Exception {
        File hpi = locateHpi();
        try (ZipFile zip = new ZipFile(hpi)) {
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            assertThat(manifestEntry).as("META-INF/MANIFEST.MF in %s", hpi).isNotNull();
            Manifest manifest;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                manifest = new Manifest(in);
            }
            pluginDependencies.putAll(
                    parsePluginDependencies(manifest.getMainAttributes().getValue("Plugin-Dependencies")));

            List<ZipEntry> libs = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith("WEB-INF/classes/") && name.endsWith(".class")) {
                    shipped.add(toClassName(name.substring("WEB-INF/classes/".length())));
                } else if (name.startsWith("WEB-INF/lib/") && name.endsWith(".jar")) {
                    libs.add(entry);
                }
            }

            for (ZipEntry lib : libs) {
                boolean scan = SCANNED_LIBS.matcher(lib.getName()).matches();
                try (ZipInputStream jar = new ZipInputStream(zip.getInputStream(lib))) {
                    ZipEntry entry;
                    while ((entry = jar.getNextEntry()) != null) {
                        if (!entry.getName().endsWith(".class")) {
                            continue;
                        }
                        shipped.add(toClassName(entry.getName()));
                        if (scan) {
                            collectReferencedClasses(jar.readAllBytes(), referenced);
                        }
                    }
                }
            }
        }
        assertThat(referenced).as("classes referenced by the scanned bundled libs").isNotEmpty();
    }

    /**
     * The core assertion: nothing the plugin ships may reference a package that is neither bundled nor
     * reachable through a declared, non-optional plugin dependency.
     */
    @Test
    public void everyExternalPackageIsProvidedByADeclaredPluginDependency() {
        Map<String, String> unmapped = new TreeMap<>();
        Map<String, String> undeclared = new TreeMap<>();
        Map<String, String> optional = new TreeMap<>();

        for (String cls : referenced) {
            if (shipped.contains(cls) || isPlatformClass(cls)) {
                continue;
            }
            String pkg = packageOf(cls);
            String provider = providerFor(pkg);
            if (provider == null) {
                unmapped.put(pkg, cls);
                continue;
            }
            Boolean isOptional = pluginDependencies.get(provider);
            if (isOptional == null) {
                undeclared.put(pkg, provider);
            } else if (isOptional) {
                optional.put(pkg, provider);
            }
        }

        assertThat(unmapped).as(
                "Package(s) referenced by a bundled jar that are neither shipped in WEB-INF/lib nor mapped "
                        + "to a providing Jenkins plugin. Each would throw NoClassDefFoundError at runtime. "
                        + "Either remove the exclusion in pom.xml so the library is bundled, or add the "
                        + "providing plugin as a direct dependency and map it in PACKAGE_PROVIDERS. "
                        + "(package -> example class)")
                .isEmpty();

        assertThat(undeclared).as(
                "Package(s) expected to come from a Jenkins plugin that is NOT in this plugin's "
                        + "Plugin-Dependencies. Reaching a plugin transitively through another plugin does not "
                        + "put it on this plugin's classloader — declare it directly in pom.xml. "
                        + "(package -> required plugin)")
                .isEmpty();

        assertThat(optional).as(
                "Package(s) whose providing plugin is declared optional. An optional dependency breaks the "
                        + "classloader delegation chain at runtime. (package -> plugin)")
                .isEmpty();
    }

    /**
     * Regression test for the {@code NoClassDefFoundError: org/json/JSONException} production failure:
     * the json-api plugin must stay a declared, non-optional dependency.
     */
    @Test
    public void jsonApiIsADeclaredNonOptionalDependency() {
        assertThat(pluginDependencies)
                .as("json-api must be a direct dependency in pom.xml — testingbotrest needs org.json at "
                        + "runtime and the raw org.json:json jar is excluded")
                .containsKey("json-api");
        assertThat(pluginDependencies.get("json-api"))
                .as("json-api must not be an optional dependency")
                .isFalse();
    }

    /**
     * The counterpart of the exclusion: because json-api supplies org.json, the HPI must not also
     * bundle it. "Fixing" a future org.json error by bundling the jar would create two copies of the
     * same classes on the classpath.
     */
    @Test
    public void orgJsonIsNotAlsoBundled() {
        assertThat(shipped.stream().filter(c -> c.startsWith("org.json.")).toList())
                .as("org.json classes are provided by the json-api plugin and must not be bundled too")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private static File locateHpi() {
        File target = new File("target");
        File[] candidates = target.listFiles((dir, name) -> name.endsWith(".hpi"));
        assertThat(candidates)
                .as("No .hpi found in %s — this is an integration test and needs `mvn verify`, not `mvn test`",
                        target.getAbsolutePath())
                .isNotNull()
                .isNotEmpty();
        return candidates[0];
    }

    static Map<String, Boolean> parsePluginDependencies(String header) {
        Map<String, Boolean> deps = new TreeMap<>();
        if (header == null || header.isBlank()) {
            return deps;
        }
        for (String token : header.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Entries look like "name:version" or "name:version;resolution:=optional".
            boolean optional = trimmed.contains("resolution:=optional");
            String name = trimmed.split("[:;]", 2)[0].trim();
            if (!name.isEmpty()) {
                deps.put(name, optional);
            }
        }
        return deps;
    }

    private static String toClassName(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static boolean isPlatformClass(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.");
    }

    /** Longest-prefix lookup in {@link #PACKAGE_PROVIDERS}. */
    private static String providerFor(String pkg) {
        String best = null;
        String bestKey = null;
        for (Map.Entry<String, String> e : PACKAGE_PROVIDERS.entrySet()) {
            String key = e.getKey();
            if (pkg.equals(key) || pkg.startsWith(key + ".")) {
                if (bestKey == null || key.length() > bestKey.length()) {
                    bestKey = key;
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    /**
     * Collects the class names a class file refers to, by walking its constant pool: every
     * {@code CONSTANT_Class} entry, plus every type named inside a descriptor-shaped
     * {@code CONSTANT_Utf8} entry (so types that only appear in method signatures are caught too).
     *
     * <p>Hand-rolled rather than using ASM so this guard has no dependency of its own — fitting, given
     * that it exists to police dependencies.</p>
     */
    static void collectReferencedClasses(byte[] classFile, Set<String> out) {
        ByteBuffer buf = ByteBuffer.wrap(classFile); // class files are big-endian, as is ByteBuffer
        buf.getInt(); // magic
        buf.getShort(); // minor version
        buf.getShort(); // major version
        int constantPoolCount = buf.getShort() & 0xFFFF;

        String[] utf8 = new String[constantPoolCount];
        int[] classNameIndex = new int[constantPoolCount];

        for (int i = 1; i < constantPoolCount; i++) {
            int tag = buf.get() & 0xFF;
            switch (tag) {
                case 1: { // Utf8
                    int length = buf.getShort() & 0xFFFF;
                    byte[] bytes = new byte[length];
                    buf.get(bytes);
                    utf8[i] = new String(bytes, StandardCharsets.UTF_8);
                    break;
                }
                case 7: // Class
                    classNameIndex[i] = buf.getShort() & 0xFFFF;
                    break;
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    buf.getShort();
                    break;
                case 15: // MethodHandle
                    buf.get();
                    buf.getShort();
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    buf.getInt();
                    break;
                case 5: // Long
                case 6: // Double
                    buf.getLong();
                    i++; // 8-byte constants occupy two pool slots
                    break;
                default:
                    throw new IllegalStateException("Unknown constant pool tag " + tag);
            }
        }

        for (int i = 1; i < constantPoolCount; i++) {
            if (classNameIndex[i] != 0) {
                addType(utf8[classNameIndex[i]], out);
            }
        }
        for (int i = 1; i < constantPoolCount; i++) {
            String value = utf8[i];
            if (value == null || value.isEmpty()) {
                continue;
            }
            char first = value.charAt(0);
            boolean descriptorShaped = first == '(' || first == '[' || (first == 'L' && value.endsWith(";"));
            if (!descriptorShaped) {
                continue;
            }
            Matcher m = DESCRIPTOR_TYPE.matcher(value);
            while (m.find()) {
                addType(m.group(1), out);
            }
        }
    }

    private static void addType(String internalName, Set<String> out) {
        if (internalName == null) {
            return;
        }
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.length() > 1 && name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.length() <= 1) {
            return; // primitive descriptor
        }
        if (INTERNAL_NAME.matcher(name).matches()) {
            out.add(name.replace('/', '.'));
        }
    }
}
