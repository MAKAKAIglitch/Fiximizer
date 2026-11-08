package com.makakaiglitch.fiximizer.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {
    public static final KeyMapping OPEN_DASHBOARD = new KeyMapping(
            "key.fiximizer.open_dashboard",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.fiximizer"
    );

    private KeyBindings() {
    }
}
