package net.vulkanmod.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tells the player once per game start which loaded mods the Vulkan renderer does not fully support: one
 * warning in the log, and one notice on the title screen (toast) or, if the game goes straight into a world,
 * in the chat. Nothing is blocked or changed. The mod ids come from
 * assets/vulkanmod/compat/renderer_gl_incompatible_mod_ids.txt and coexistence_incompatible_mod_ids.txt.
 * Client only; the listeners run on the render thread and do nothing once the notice is out.
 */
public final class UnsupportedModNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH");
    private static final String COEXISTENCE_LIST = "/assets/vulkanmod/compat/coexistence_incompatible_mod_ids.txt";
    /** Distant Horizons is on the renderer list as an OpenGL renderer, but VSDH carries its own adapter for it. */
    private static final Set<String> INTEGRATED = Set.of("distanthorizons");
    private static final long TOAST_MILLIS = 15000L;

    private static String loadedNames = "";
    private static boolean shown;

    private UnsupportedModNotice() {
    }

    /** Called once from client setup. */
    public static void init() {
        Set<String> ids = new LinkedHashSet<>();
        addAll(ids, CompatMods.RENDERER_GL_MOD_IDS);
        addAll(ids, CompatMods.loadLines(COEXISTENCE_LIST));

        List<String> names = new ArrayList<>();
        for (String id : ids) {
            if (INTEGRATED.contains(id)) {
                continue;
            }
            ModList.get().getModContainerById(id)
                    .ifPresent(container -> names.add(container.getModInfo().getDisplayName()));
        }
        if (names.isEmpty()) {
            return;
        }

        loadedNames = String.join(", ", names);
        LOGGER.warn("These mods are not fully supported by the VSDH Vulkan renderer; display errors are possible: {}. "
                + "Nothing is blocked. If something looks wrong, test once without them.", loadedNames);

        NeoForge.EVENT_BUS.addListener(UnsupportedModNotice::onScreenInit);
        NeoForge.EVENT_BUS.addListener(UnsupportedModNotice::onWorldJoin);
    }

    private static void addAll(Set<String> destination, String[] ids) {
        for (String id : ids) {
            destination.add(id.toLowerCase(Locale.ROOT));
        }
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (shown || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        shown = true;

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getToasts().addToast(SystemToast.multiline(minecraft, new SystemToast.SystemToastId(TOAST_MILLIS),
                Component.translatable("vsdh.notice.unsupportedMods.title"),
                Component.translatable("vsdh.notice.unsupportedMods.message", loadedNames)));
    }

    private static void onWorldJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (shown) {
            return;
        }
        shown = true;

        Minecraft.getInstance().gui.getChat().addMessage(
                Component.translatable("vsdh.notice.unsupportedMods.chat", loadedNames));
    }
}
