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
    private final TimerUtil timer = new TimerUtil();
    
    private boolean active;
    private boolean stopForward;
    private long delayTicks;
    private long durationTicks;
    
    public final FloatProperty delay = new FloatProperty("Delay", 3.0F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration", 1.5F, 0.5F, 5.0F);

    public Wtap() {
        super("WTap", false);
    }

    private boolean canTrigger() {
        if (mc.thePlayer.movementInput.moveForward < 0.8F) return false;
        if (mc.thePlayer.isCollidedHorizontally) return false;
        if (mc.thePlayer.getFoodStats().getFoodLevel() <= 6.0F && !mc.thePlayer.capabilities.allowFlying) return false;
        if (mc.thePlayer.isPotionActive(Potion.blindness)) return false;
        if (mc.thePlayer.isUsingItem() && !mc.thePlayer.isSprinting()) return false;
        
        return mc.thePlayer.isSprinting() || mc.gameSettings.keyBindSprint.isKeyDown();
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!active) return;
        
        if (stopForward || !canTrigger()) {
            reset();
            return;
        }
        
        if (delayTicks > 0) {
            delayTicks -= 50L;
            return;
        }
        
        if (durationTicks > 0) {
            durationTicks -= 50L;
            stopForward = true;
            mc.thePlayer.movementInput.moveForward = 0.0F;
        } else {
            active = false;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) return;
        if (active || !timer.hasTimeElapsed(500L)) return;
        if (!mc.thePlayer.isSprinting()) return;
        
        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() != Action.ATTACK) return;
            
            timer.reset();
            active = true;
            stopForward = false;
            delayTicks = (long) (50.0F * delay.getValue());
            durationTicks = (long) (50.0F * duration.getValue());
        }
    }
    
    private void reset() {
        active = false;
        stopForward = false;
        delayTicks = 0L;
        durationTicks = 0L;
    }
    
    public void onDisable() {
        reset();
        timer.reset();
    }
}
