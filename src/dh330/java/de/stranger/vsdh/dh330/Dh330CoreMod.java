package de.stranger.vsdh.dh330;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TargetType;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets the one VSDH JAR run with Distant Horizons 3.3.0: when exactly that DH is installed, VSDH's
 * own classes that reference a twinned DH class are rewritten as they load (see {@link Dh330Mapping}).
 * With any other DH version, or without DH, this contributes no transformer and nothing changes.
 *
 * <p>Lives in a library JAR nested in the VSDH JAR because NeoForge reads Java coremods from the
 * plugin layer. It must not reference game or mod classes. It never throws: if anything is off it
 * logs and stands down, and VSDH (DhSupport) then keeps its DH adapter off instead of crashing.
 */
public final class Dh330CoreMod implements ICoreMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("vsdh-dh330");
    private static final String DH_MOD = "distanthorizons";
    private static final String VSDH_MOD = "vsdh";

    @Override public Iterable<? extends ITransformer<?>> getTransformers() {
        try {
            return plan();
        } catch (Throwable failure) {
            LOGGER.error("VSDH could not prepare its Distant Horizons 3.3.0 adapter; VSDH's DH renderer will stay off.", failure);
            return List.of();
        }
    }

    private static List<ITransformer<ClassNode>> plan() throws Exception {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) return List.of();
        ModFileInfo dh = mods.getModFileById(DH_MOD);
        ModFileInfo vsdh = mods.getModFileById(VSDH_MOD);
        if (dh == null || vsdh == null) return List.of();
        String version = dh.getMods().stream().filter(mod -> DH_MOD.equals(mod.getModId())).findFirst()
                .map(mod -> mod.getVersion().toString()).orElse("");
        if (!Dh330Mapping.DH_VERSION.equals(version)) return List.of();

        String fileName = dh.getFile().getFileName();
        Path file = dh.getFile().getFilePath();
        String sha1 = file != null && Files.isRegularFile(file) ? Dh330Mapping.sha1(file) : null;
        switch (Dh330Mapping.decide(version, sha1, Boolean.getBoolean(Dh330Mapping.ALLOW_UNPINNED_PROPERTY))) {
            case REFUSED_UNPINNED -> {
                LOGGER.error("Distant Horizons {} ({}) is not the build VSDH was verified with (SHA-1 {}, found {}). "
                                + "VSDH's DH renderer stays off. Install the original DH {} file for Minecraft 1.21.1 or another "
                                + "supported DH version; -D{}=true adapts this file anyway, untested.",
                        version, fileName, Dh330Mapping.PINNED_SHA1, sha1, version, Dh330Mapping.ALLOW_UNPINNED_PROPERTY);
                return List.of();
            }
            case REMAP_UNPINNED -> LOGGER.warn("Distant Horizons {} ({}) has the unknown SHA-1 {}; adapting it anyway because -D{}=true.",
                    version, fileName, sha1, Dh330Mapping.ALLOW_UNPINNED_PROPERTY);
            case NOT_NEEDED -> {
                return List.of();
            }
            case REMAP -> { }
        }

        Path dhRoot = dh.getFile().getSecureJar().getRootPath();
        Map<String, String> types = Dh330Mapping.platformTypes(Dh330Mapping.classEntries(dhRoot.resolve(Dh330Mapping.DH_ROOT))
                .stream().map(name -> Dh330Mapping.DH_ROOT + name).toList());
        if (types.isEmpty()) {
            LOGGER.error("Distant Horizons {} ({}) contains none of the expected NeoForge class twins; VSDH's DH renderer stays off.",
                    version, fileName);
            return List.of();
        }

        Path ownRoot = vsdh.getFile().getSecureJar().getRootPath();
        Set<ITransformer.Target<ClassNode>> targets = new HashSet<>();
        for (String entry : Dh330Mapping.classEntries(ownRoot)) {
            if (entry.startsWith("META-INF/")) continue;
            byte[] bytes = Files.readAllBytes(ownRoot.resolve(entry));
            if (Dh330Mapping.mentionsDistantHorizons(bytes) && Dh330Mapping.remap(bytes, types) != null)
                targets.add(ITransformer.Target.targetClass(entry.substring(0, entry.length() - ".class".length()).replace('/', '.')));
        }
        if (targets.isEmpty()) {
            LOGGER.error("Found no VSDH class to adapt to Distant Horizons {}; VSDH's DH renderer stays off.", version);
            return List.of();
        }
        LOGGER.info("Distant Horizons {} detected: adapting {} VSDH classes to its {} NeoForge class names at load time.",
                version, targets.size(), types.size());
        return List.of(new Rename(types, Set.copyOf(targets)));
    }

    private record Rename(Map<String, String> types, Set<Target<ClassNode>> targets) implements ITransformer<ClassNode> {
        @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
            ClassNode output = new ClassNode();
            input.accept(new ClassRemapper(output, new Dh330Mapping.TypeRemapper(types)));
            return output;
        }

        @Override public TransformerVoteResult castVote(ITransformerVotingContext context) {
            return TransformerVoteResult.YES;
        }

        @Override public TargetType<ClassNode> getTargetType() {
            return TargetType.CLASS;
        }
    }
}
