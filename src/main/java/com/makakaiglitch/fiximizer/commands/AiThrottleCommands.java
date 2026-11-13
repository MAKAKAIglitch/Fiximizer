package com.makakaiglitch.fiximizer.commands;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.ai.AiThrottleController;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class AiThrottleCommands {
    private AiThrottleCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("fiximizer")
                        .then(Commands.literal("throttle")
                                .executes(context -> report(context.getSource()))
                                .then(Commands.literal("off")
                                        .executes(context -> set(context.getSource(), 2)))
                                .then(Commands.literal("2")
                                        .executes(context -> set(context.getSource(), 2)))
                                .then(Commands.literal("4")
                                        .executes(context -> set(context.getSource(), 4)))
                                .then(Commands.literal("8")
                                        .executes(context -> set(context.getSource(), 8)))
                                .then(Commands.literal("config")
                                        .executes(context -> {
                                            AiThrottleController.clearRuntimeOverride();
                                            return report(context.getSource());
                                        }))
                        )
        );
    }

    private static int set(CommandSourceStack source, int interval) {
        AiThrottleController.setRuntimeInterval(interval);
        return report(source);
    }

    private static int report(CommandSourceStack source) {
        int interval = AiThrottleController.effectiveInterval();
        String message = interval == 2
                ? "Fiximizer AI throttle: OFF (vanilla full goal checks every 2 ticks)."
                : "Fiximizer AI throttle: full goal checks every " + interval
                    + " ticks; running goals still update every AI tick.";
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
