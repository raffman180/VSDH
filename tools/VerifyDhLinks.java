import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import de.stranger.vsdh.dh330.Dh330Mapping;

/**
 * Static link check of the single VSDH JAR against Distant Horizons JARs (never shipped, never run).
 *
 * <p>For each DH JAR it takes the path the game would take (Dh330Mapping decides, exactly as at load
 * time, whether VSDH's classes are rewritten), then checks that every DH class and member referenced
 * by VSDH exists in that DH JAR under that name: mixin targets, shadows, injection points, class
 * references, method and field references. Classes and hooks that the runtime selects by DH version
 * (DhMixinPlugin, ApiDhIntegration) are selected here by the same structural tests.
 *
 * <p>Usage: {@code java -cp <asm;dh330 classes> tools/VerifyDhLinks.java <VSDH JAR> <DH JAR>...}
 */
class VerifyDhLinks {
    private static final String DH = "com/seibel/distanthorizons/";
    private static final String MIXIN_CONFIG = "vsdh.mixins.json";
    private static final List<String> SUPPORTED = List.of("3.2.0-b", "3.3.0", "3.3.1", "3.3.2", "3.3.3");
    private static final String LEGACY_API = "com/braffolk/dhvulkan/api/LegacyVkRenderApiDefinition";
    private static final String MODERN_API = "com/braffolk/dhvulkan/api/ModernVkRenderApiDefinition";

    /**
     * References that do not exist in a DH version and are known not to be reached there. Each entry
     * names the guard in VSDH's source; anything else that is missing fails the check.
     */
    private static final Map<String, String> TOLERATED = new LinkedHashMap<>();

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected the VSDH JAR and at least one DH JAR");
        loadTolerated();
        Map<String, byte[]> vsdh = readJar(Path.of(args[0]));
        System.out.println("VSDH JAR: " + Path.of(args[0]).getFileName() + " (" + vsdh.size() + " entries)");
        checkPackaging(vsdh);
        for (int at = 1; at < args.length; at++) checkAgainst(vsdh, Path.of(args[at]));
        System.out.println();
        System.out.println(failures == 0 ? "RESULT: PASS (static link check; not an in-game test)" : "RESULT: FAIL, " + failures + " problem(s)");
        if (failures != 0) System.exit(1);
    }

    private static void loadTolerated() {
        // Keys are "<dh version>|<vsdh class>|<reference>". The JVM links lazily: a type that appears only in
        // the signature or body of a method DH never calls is never loaded.
        String depth = "DH 3.2.0-b has no depth-range query; it never calls getDepthRange()";
        TOLERATED.put("3.2.0-b|" + LEGACY_API + "|class " + DH + "core/render/EDhDepthRange", depth);
        TOLERATED.put("3.2.0-b|" + LEGACY_API + "|field " + DH + "core/render/EDhDepthRange.NEG_ONE_TO_POS_ONE L" + DH
                + "core/render/EDhDepthRange; (class " + DH + "core/render/EDhDepthRange missing)", depth);
        TOLERATED.put("3.2.0-b|com/braffolk/dhvulkan/api/VkRenderApiDefinition|class " + DH
                        + "core/wrapperInterfaces/render/renderPass/IDhAntiAliasRenderer",
                "DH 3.2.0-b has no anti-alias pass; it never calls getAntiAliasRenderer()");
    }

    // ------------------------------------------------------------------------------------------------ packaging

    private static void checkPackaging(Map<String, byte[]> vsdh) throws IOException {
        String toml = text(vsdh, "META-INF/neoforge.mods.toml");
        Matcher dependency = Pattern.compile("modId=\"distanthorizons\"\\s+type=\"(\\w+)\"\\s+versionRange=\"([^\"]+)\"").matcher(toml);
        if (!dependency.find()) fail("neoforge.mods.toml has no distanthorizons dependency");
        else {
            Set<String> accepted = new TreeSet<>();
            for (String part : dependency.group(2).split(",")) accepted.add(part.replace("[", "").replace("]", "").trim());
            System.out.println("mods.toml: distanthorizons type=" + dependency.group(1) + " versionRange=" + dependency.group(2));
            if (!dependency.group(1).equals("optional")) fail("distanthorizons must be an optional dependency");
            if (!accepted.equals(new TreeSet<>(SUPPORTED))) fail("mods.toml accepts " + accepted + ", expected " + SUPPORTED);
        }
        String nested = vsdh.keySet().stream().filter(name -> name.matches("META-INF/jarjar/vsdh-dh330-.*\\.jar")).findFirst().orElse(null);
        if (nested == null) {
            fail("The nested vsdh-dh330 library is missing");
            return;
        }
        if (!text(vsdh, "META-INF/jarjar/metadata.json").contains("\"" + nested + "\"")) fail("JarJar metadata does not list " + nested);
        Map<String, byte[]> library = new HashMap<>();
        String modType;
        try (var input = new JarInputStream(new java.io.ByteArrayInputStream(vsdh.get(nested)))) {
            modType = input.getManifest() == null ? null : input.getManifest().getMainAttributes().getValue("FMLModType");
            for (var entry = input.getNextJarEntry(); entry != null; entry = input.getNextJarEntry()) library.put(entry.getName(), input.readAllBytes());
        }
        String service = text(library, "META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod").trim();
        System.out.println("nested adapter: " + nested + " FMLModType=" + modType + " ICoreMod=" + service);
        if (!"LIBRARY".equals(modType)) fail(nested + " must declare FMLModType: LIBRARY");
        if (service.isEmpty() || !library.containsKey(service.replace('.', '/') + ".class")) fail(nested + " does not provide its ICoreMod");
        for (String name : library.keySet())
            if (name.endsWith(".class") && vsdh.containsKey(name)) fail("Class in both the mod and the nested library: " + name);
        for (var entry : library.entrySet())
            if (entry.getKey().endsWith(".class")) for (String reference : classReferences(entry.getValue()))
                if (reference.startsWith("net/minecraft/") || reference.startsWith("com/seibel/") || reference.startsWith("com/braffolk/")
                        || reference.startsWith("net/vulkanmod/") || (reference.startsWith("de/stranger/vsdh/") && !reference.startsWith("de/stranger/vsdh/dh330/")))
                    fail("The plugin-layer library must not reference game or mod classes: " + entry.getKey() + " -> " + reference);
    }

    // ------------------------------------------------------------------------------------------------ one DH version

    private static void checkAgainst(Map<String, byte[]> original, Path dhJar) throws Exception {
        System.out.println();
        Map<String, byte[]> dhEntries = readJar(dhJar);
        String version;
        try (var jar = new JarFile(dhJar.toFile())) {
            version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
        String sha1 = Dh330Mapping.sha1(dhJar);
        var decision = Dh330Mapping.decide(version, sha1, false);
        Map<String, String> types = Dh330Mapping.platformTypes(dhEntries.keySet());
        boolean twins = dhEntries.containsKey(DH + "core/config/Config_neoforge.class");
        System.out.println("== DH " + version + "  " + dhJar.getFileName() + "  SHA-1 " + sha1);
        System.out.println("   load-time decision: " + decision + "; twinned DH classes: " + types.size());
        if (!SUPPORTED.contains(version)) fail("DH " + version + " is not in the supported list");
        if (twins != decision.remap) fail("DH " + version + ": twinned classes " + twins + " but adapter " + decision);
        if (twins != types.containsKey(DH + "core/config/Config")) fail("DH " + version + ": DhSupport's twin probe disagrees with the mapping");

        Map<String, byte[]> vsdh = new TreeMap<>();
        Set<String> adapted = new TreeSet<>();
        for (var entry : original.entrySet()) {
            if (!entry.getKey().endsWith(".class") || entry.getKey().startsWith("META-INF/")) continue;
            byte[] bytes = entry.getValue();
            if (decision.remap && Dh330Mapping.mentionsDistantHorizons(bytes)) {
                byte[] rewritten = Dh330Mapping.remap(bytes, types);
                if (rewritten != null) {
                    bytes = rewritten;
                    adapted.add(entry.getKey().substring(0, entry.getKey().length() - 6));
                }
            }
            vsdh.put(entry.getKey().substring(0, entry.getKey().length() - 6), bytes);
        }
        System.out.println("   VSDH classes rewritten on this path: " + adapted.size());
        if (!decision.remap && !types.isEmpty()) {
            // Later DH versions still twin a few loader classes. VSDH must not name any of them by the plain name.
            Set<String> plain = new TreeSet<>();
            for (var entry : vsdh.entrySet())
                if (Dh330Mapping.mentionsDistantHorizons(entry.getValue()) && Dh330Mapping.remap(entry.getValue(), types) != null) plain.add(entry.getKey());
            System.out.println("   VSDH classes naming one of those twinned classes by its plain name: " + plain);
            if (!plain.isEmpty()) fail("DH " + version + ": " + plain + " would use the Fabric half of a twinned class");
        }
        if (decision.remap) {
            for (String marker : List.of("com/braffolk/dhvulkan/DhSupport", "com/braffolk/dhvulkan/mixin/DhMixinPlugin"))
                if (!adapted.contains(marker)) fail("DH " + version + ": " + marker + " must be rewritten (it reports/uses the adapted names)");
            checkFrames(version, vsdh, adapted, dhEntries);
        }

        var index = new DhIndex(dhEntries);
        // The same structural tests as DhMixinPlugin and ApiDhIntegration, on the names this path uses.
        String repository = types.getOrDefault(DH + "core/sql/repo/FullDataSourceV2Repo", DH + "core/sql/repo/FullDataSourceV2Repo");
        String queue = types.getOrDefault(DH + "core/api/internal/chunkUpdating/ChunkUpdateQueueManager", DH + "core/api/internal/chunkUpdating/ChunkUpdateQueueManager");
        String depthApi = types.getOrDefault(DH + "api/enums/config/EDhApiDepthDirection", DH + "api/enums/config/EDhApiDepthDirection");
        boolean regeneration = index.hasMethod(repository, "getChildPositionsToRegen", "(IIII)Lit/unimi/dsi/fastutil/longs/LongArrayList;");
        boolean queueLoop = index.hasMethod(queue, "runQueueingLoop", null);
        boolean modernDepth = index.exists(depthApi);
        System.out.println("   selectors: regenerationQueue=" + regeneration + " chunkUpdateLoop=" + queueLoop + " depthApi=" + (modernDepth ? "modern" : "legacy"));

        Problems problems = new Problems(version);
        Set<String> mixins = new TreeSet<>();
        String config = text(original, MIXIN_CONFIG);
        Matcher packageName = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(config);
        if (!packageName.find()) throw new IOException("No package in " + MIXIN_CONFIG);
        Matcher client = Pattern.compile("\"client\"\\s*:\\s*\\[([^\\]]*)]").matcher(config);
        if (!client.find()) throw new IOException("No client mixins in " + MIXIN_CONFIG);
        int applied = 0;
        List<String> skipped = new ArrayList<>();
        for (String entry : client.group(1).split(",")) {
            String name = packageName.group(1).replace('.', '/') + "/" + entry.replace("\"", "").trim().replace('.', '/');
            mixins.add(name);
            String simple = name.substring(name.lastIndexOf('/') + 1);
            boolean apply = switch (simple) {
                case "MixinRegenQueryScope", "MixinUpdatePropagatorShutdown" -> regeneration;
                case "MixinLegacyUpdateQueryScope", "MixinLegacyUpdatePropagatorShutdown" -> !regeneration;
                case "MixinChunkUpdateQueueBackoff" -> queueLoop;
                default -> true;
            };
            if (!vsdh.containsKey(name)) {
                problems.add(name, "mixin class is not in the JAR");
                continue;
            }
            if (!apply) {
                skipped.add(simple);
                continue;
            }
            applied++;
            checkMixin(name, vsdh.get(name), index, problems);
        }
        System.out.println("   DH hooks applied: " + applied + ", switched off by DhMixinPlugin here: " + skipped);

        Set<String> classes = new TreeSet<>();
        int[] members = new int[1];
        for (var entry : vsdh.entrySet()) {
            String owner = entry.getKey();
            if (mixins.contains(owner)) continue;
            if (owner.equals(LEGACY_API) && modernDepth || owner.equals(MODERN_API) && !modernDepth) continue;
            if (!Dh330Mapping.mentionsDistantHorizons(entry.getValue())) continue;
            for (String reference : classReferences(entry.getValue())) {
                if (!reference.startsWith(DH)) continue;
                classes.add(reference);
                if (!index.exists(reference)) problems.add(owner, "class " + reference);
            }
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String target, String method, String desc, boolean isInterface) {
                            member(target, method, desc, true);
                        }

                        @Override public void visitFieldInsn(int opcode, String target, String field, String desc) {
                            member(target, field, desc, false);
                        }

                        @Override public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap, Object... arguments) {
                            for (Object argument : arguments)
                                if (argument instanceof Handle handle)
                                    member(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() >= Opcodes.H_INVOKEVIRTUAL);
                        }

                        private void member(String target, String name, String desc, boolean method) {
                            if (!target.startsWith(DH)) return;
                            members[0]++;
                            String found = method ? index.findMethod(target, name, desc) : index.findField(target, name, desc);
                            if (found != null) problems.add(owner, (method ? "method " : "field ") + target + "." + name + (method ? "" : " ") + desc + found);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        System.out.println("   outside the hooks: " + classes.size() + " DH classes and " + members[0] + " DH member references checked"
                + " (skipped the " + (modernDepth ? "legacy" : "modern") + " depth subclass, which ApiDhIntegration does not load here)");
        problems.report();
    }

    // ------------------------------------------------------------------------------------------------ mixins

    private static void checkMixin(String name, byte[] bytes, DhIndex index, Problems problems) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode annotation : annotations(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (Object value : list(annotation, "value")) targets.add(((Type) value).getInternalName());
            for (Object value : list(annotation, "targets")) targets.add(((String) value).replace('.', '/'));
        }
        if (targets.isEmpty()) problems.add(name, "no @Mixin target");
        for (String target : targets) {
            if (!target.startsWith(DH)) continue; // Minecraft target (the DH composite hook): not part of this check
            if (!index.exists(target)) {
                problems.add(name, "mixin target " + target);
                continue;
            }
            for (FieldNode field : node.fields)
                if (has(field.visibleAnnotations, field.invisibleAnnotations, "Shadow")) {
                    String found = index.findField(target, field.name, field.desc);
                    if (found != null) problems.add(name, "@Shadow field " + target + "." + field.name + " " + field.desc + found);
                }
            for (MethodNode method : node.methods) {
                if (has(method.visibleAnnotations, method.invisibleAnnotations, "Shadow")) {
                    String found = index.findMethod(target, method.name, method.desc);
                    if (found != null) problems.add(name, "@Shadow method " + target + "." + method.name + method.desc + found);
                }
                for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    for (Object selector : list(annotation, "method")) {
                        String text = (String) selector;
                        int paren = text.indexOf('(');
                        String methodName = paren < 0 ? text : text.substring(0, paren);
                        if (!index.hasMethod(target, methodName, paren < 0 ? null : text.substring(paren)))
                            problems.add(name, "injection method " + target + "." + text);
                    }
                    for (Object at : list(annotation, "at")) checkAt((AnnotationNode) at, name, index, problems);
                    if (value(annotation, "at") instanceof AnnotationNode at) checkAt(at, name, index, problems);
                }
            }
        }
        // Types in handler signatures and bodies must exist as well.
        for (String reference : classReferences(bytes))
            if (reference.startsWith(DH) && !index.exists(reference)) problems.add(name, "class " + reference);
    }

    private static void checkAt(AnnotationNode at, String name, DhIndex index, Problems problems) {
        if (!(value(at, "target") instanceof String target) || !target.startsWith("L" + DH)) return;
        int semi = target.indexOf(';');
        String owner = target.substring(1, semi);
        String rest = target.substring(semi + 1);
        int paren = rest.indexOf('(');
        int colon = rest.indexOf(':');
        String found = paren >= 0 ? index.findMethod(owner, rest.substring(0, paren), rest.substring(paren))
                : colon >= 0 ? index.findField(owner, rest.substring(0, colon), rest.substring(colon + 1))
                : index.exists(owner) ? null : " (class missing)";
        if (found != null) problems.add(name, "@At target " + target + found);
    }

    @SafeVarargs private static List<AnnotationNode> annotations(List<AnnotationNode>... lists) {
        List<AnnotationNode> all = new ArrayList<>();
        for (List<AnnotationNode> list : lists) if (list != null) all.addAll(list);
        return all;
    }

    private static boolean has(List<AnnotationNode> visible, List<AnnotationNode> invisible, String simpleName) {
        return annotations(visible, invisible).stream().anyMatch(annotation -> annotation.desc.endsWith("/" + simpleName + ";"));
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int at = 0; at < annotation.values.size(); at += 2) if (annotation.values.get(at).equals(key)) return annotation.values.get(at + 1);
        return null;
    }

    private static List<?> list(AnnotationNode annotation, String key) {
        return value(annotation, key) instanceof List<?> list ? list : List.of();
    }

    // ------------------------------------------------------------------------------------------------ frames

    /**
     * ModLauncher recomputes stack map frames of every class a transformer touched, and needs the
     * hierarchy of each pair of types that meet at a branch. Report the types it would ask for that
     * neither VSDH, DH nor the JDK provide, so that an optional mod's class can never be required.
     */
    private static void checkFrames(String version, Map<String, byte[]> vsdh, Set<String> adapted, Map<String, byte[]> dh) {
        Set<String> external = new TreeSet<>();
        for (String name : adapted) {
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override protected String getCommonSuperClass(String first, String second) {
                    for (String type : List.of(first, second))
                        if (!vsdh.containsKey(type) && !dh.containsKey(type + ".class") && !type.startsWith("java/")) external.add(type);
                    return "java/lang/Object";
                }
            };
            try {
                new ClassReader(vsdh.get(name)).accept(writer, ClassReader.SKIP_FRAMES);
                writer.toByteArray();
            } catch (RuntimeException failure) {
                fail("DH " + version + ": frames of rewritten " + name + " cannot be recomputed: " + failure);
            }
        }
        Set<String> unexpected = new TreeSet<>();
        for (String type : external)
            if (!type.startsWith("net/minecraft/") && !type.startsWith("com/mojang/") && !type.startsWith("org/joml/")
                    && !type.startsWith("org/lwjgl/") && !type.startsWith("it/unimi/dsi/fastutil/")) unexpected.add(type);
        System.out.println("   frame recomputation of rewritten classes: " + external.size() + " types from Minecraft and its libraries, other: " + unexpected);
        if (!unexpected.isEmpty()) fail("DH " + version + ": frame recomputation would need " + unexpected);
    }

    // ------------------------------------------------------------------------------------------------ DH index

    private static final class DhIndex {
        private final Map<String, byte[]> entries;
        private final Map<String, ClassNode> nodes = new HashMap<>();

        DhIndex(Map<String, byte[]> entries) {
            this.entries = entries;
        }

        boolean exists(String name) {
            return entries.containsKey(name + ".class");
        }

        ClassNode node(String name) {
            if (!exists(name)) return null;
            return nodes.computeIfAbsent(name, key -> {
                ClassNode node = new ClassNode();
                new ClassReader(entries.get(key + ".class")).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            });
        }

        boolean hasMethod(String owner, String name, String desc) {
            ClassNode node = node(owner);
            return node != null && node.methods.stream().anyMatch(method -> method.name.equals(name) && (desc == null || method.desc.equals(desc)));
        }

        /** null when found; otherwise a short reason. */
        String findMethod(String owner, String name, String desc) {
            return find(owner, name, desc, true, new HashSet<>());
        }

        String findField(String owner, String name, String desc) {
            return find(owner, name, desc, false, new HashSet<>());
        }

        private String find(String owner, String name, String desc, boolean method, Set<String> seen) {
            if (owner.startsWith("[")) return null;
            if (!seen.add(owner)) return " (missing)";
            ClassNode node = node(owner);
            if (node == null) return owner.startsWith(DH) ? " (class " + owner + " missing)" : external(owner, name, desc, method);
            if (method ? node.methods.stream().anyMatch(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                    : node.fields.stream().anyMatch(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))) return null;
            // Signature-polymorphic and enum-synthesized members are declared, so no special cases are needed.
            List<String> parents = new ArrayList<>();
            if (node.superName != null) parents.add(node.superName);
            parents.addAll(node.interfaces);
            String reason = " (missing)";
            for (String parent : parents) {
                String result = find(parent, name, desc, method, seen);
                if (result == null) return null;
                if (result.startsWith(" (unverified")) reason = result;
            }
            return reason;
        }

        /** Members inherited from outside DH: the JDK is checked, other libraries cannot be. */
        private static String external(String owner, String name, String desc, boolean method) {
            if (!owner.startsWith("java/")) return " (unverified: inherited from " + owner + ")";
            try {
                for (Class<?> type = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()); type != null; type = type.getSuperclass()) {
                    if (method) {
                        for (var candidate : type.getDeclaredMethods())
                            if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(desc) && !Modifier.isPrivate(candidate.getModifiers())) return null;
                        for (Class<?> parent : type.getInterfaces())
                            if (external(Type.getInternalName(parent), name, desc, true) == null) return null;
                    } else {
                        for (var candidate : type.getDeclaredFields())
                            if (candidate.getName().equals(name) && Type.getDescriptor(candidate.getType()).equals(desc)) return null;
                    }
                }
            } catch (ClassNotFoundException | LinkageError missing) {
                return " (missing: " + owner + ")";
            }
            return " (missing)";
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final class Problems {
        private final String version;
        private final Map<String, Set<String>> missing = new TreeMap<>();
        private final Map<String, Set<String>> unverified = new TreeMap<>();
        private final List<String> tolerated = new ArrayList<>();

        Problems(String version) {
            this.version = version;
        }

        void add(String owner, String reference) {
            String key = version + "|" + owner + "|" + reference;
            if (TOLERATED.containsKey(key)) tolerated.add(owner + " -> " + reference + "  [" + TOLERATED.get(key) + "]");
            else (reference.contains("(unverified") ? unverified : missing).computeIfAbsent(owner, ignored -> new TreeSet<>()).add(reference);
        }

        void report() {
            for (String line : tolerated) System.out.println("   tolerated: " + line);
            int unverifiedCount = unverified.values().stream().mapToInt(Set::size).sum();
            if (unverifiedCount != 0) {
                System.out.println("   not verifiable from the DH JAR alone: " + unverifiedCount);
                unverified.forEach((owner, references) -> references.forEach(reference -> System.out.println("     " + owner + " -> " + reference)));
            }
            int count = missing.values().stream().mapToInt(Set::size).sum();
            System.out.println("   " + (count == 0 ? "LINK OK" : "MISSING: " + count));
            missing.forEach((owner, references) -> references.forEach(reference -> System.out.println("     " + owner + " -> " + reference)));
            failures += count;
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("   PROBLEM: " + message);
    }

    @SuppressWarnings("deprecation") // the no-argument Remapper constructor exists in every ASM 9
    private static Set<String> classReferences(byte[] bytes) {
        Set<String> names = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), 0);
        return names;
    }

    private static String text(Map<String, byte[]> entries, String name) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null) {
            fail("Missing " + name);
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readJar(Path path) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (var jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream().toList()) {
                if (entry.isDirectory()) continue;
                try (InputStream stream = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), stream.readAllBytes());
                }
            }
        }
        return entries;
    }
}
