package com.makakaiglitch.fiximizer.mixin;

import com.makakaiglitch.fiximizer.ai.AiThrottleController;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Alters only full goal-start checks in Mob.serverAiStep().
 * Existing running goals continue to be evaluated on every AI tick.
 */
@Mixin(Mob.class)
public abstract class MobAiThrottleMixin {
    @Redirect(
            method = "serverAiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"
            ),
            require = 2
    )
    private void fiximizer$throttleFullGoalChecks(GoalSelector selector) {
        Mob mob = (Mob) (Object) this;
        if (AiThrottleController.shouldRunFullCheck(mob)) {
            selector.tick();
        } else {
            selector.tickRunningGoals(false);
        }
    }
}
