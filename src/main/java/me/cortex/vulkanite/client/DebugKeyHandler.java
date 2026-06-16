package me.cortex.vulkanite.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Handles key bindings for debug visualization controls.
 *
 * Key bindings:
 * - G: Reserved for future debug functionality
 */
public class DebugKeyHandler {

    private static KeyBinding cycleDebugCellKey;

    /**
     * Initialize the key bindings and register event handlers.
     * Should be called during mod initialization.
     */
    public static void init() {
        // Register the cycle debug cell key binding (G key - doesn't conflict with common actions)
        cycleDebugCellKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.vulkanite.cycle_debug_cell",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.vulkanite.debug"
        ));

        // Register tick event to check for key presses
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (cycleDebugCellKey.wasPressed()) {
                handleCycleDebugCell();
            }
        });
    }

    /**
     * Handle the cycle debug cell key press.
     * Currently a placeholder for future debug functionality.
     */
    private static void handleCycleDebugCell() {
        System.out.println("[Vulkanite Debug] G key pressed. Debug functionality not yet implemented.");
        if (net.minecraft.client.MinecraftClient.getInstance().player != null) {
            net.minecraft.client.MinecraftClient.getInstance().player.sendMessage(Text.literal("§e[Vulkanite Debug]§r Debug functionality not yet implemented."), true);
        }
    }
}
