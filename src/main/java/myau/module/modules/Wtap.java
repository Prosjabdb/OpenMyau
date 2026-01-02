package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.util.TimerUtil;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.potion.Potion;

public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty delay = new FloatProperty("Delay", 5.5F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.5F, 1.0F, 5.0F);

    private final TimerUtil timer = new TimerUtil();
    private boolean active = false;
    private boolean stopForward = false;
    private int delayTicks = 0;
    private int durationTicks = 0;

    public Wtap() {
        super("WTap", false);
    }

    private boolean canTrigger() {
        if (mc.thePlayer == null || mc.thePlayer.movementInput == null) return false;

        return mc.thePlayer.movementInput.moveForward >= 0.8F
                && !mc.thePlayer.isCollidedHorizontally
                && (mc.thePlayer.getFoodStats().getFoodLevel() > 6.0F || mc.thePlayer.capabilities.allowFlying)
                && (mc.thePlayer.isSprinting()
                || (!mc.thePlayer.isUsingItem()
                && !mc.thePlayer.isPotionActive(Potion.blindness)
                && mc.gameSettings.keyBindSprint.isKeyDown()));
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!active || mc.thePlayer == null || mc.thePlayer.movementInput == null) return;

        // Reduce delay ticks
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        // Handle duration ticks
        if (durationTicks > 0) {
            durationTicks--;
            stopForward = true;
            mc.thePlayer.movementInput.moveForward = 0.0F;
        } else {
            active = false;
            stopForward = false;
        }

        // Cancel Wtap if conditions not met
        if (!stopForward && !canTrigger()) {
            active = false;
            delayTicks = 0;
            durationTicks = 0;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) return;
        if (mc.thePlayer == null) return;

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() != Action.ATTACK) return;

            if (!active && mc.thePlayer.isSprinting() && timer.hasTimeElapsed(500L)) {
                timer.reset();
                active = true;
                stopForward = false;

                // Convert float seconds to ticks
                delayTicks = Math.max(1, Math.round(delay.getValue() * 20));
                durationTicks = Math.max(1, Math.round(duration.getValue() * 20));
            }
        }
    }
}
