package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.util.TimerUtil;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C02PacketUseEntity.Action;
import net.minecraft.potion.Potion;

public class Wtap extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    private boolean active = false;
    private boolean stopForward = false;
    private long delayTicks = 0L;
    private long durationTicks = 0L;

    // Configurable properties (using FloatProperty for cooldown since IntegerProperty doesn't exist)
    public final FloatProperty delay = new FloatProperty("Delay (ticks)", 5.5F, 0.0F, 10.0F);
    public final FloatProperty duration = new FloatProperty("Duration (ticks)", 1.5F, 1.0F, 5.0F);
    public final FloatProperty cooldownMs = new FloatProperty("Cooldown (ms)", 500.0F, 0.0F, 2000.0F);
    public final BooleanProperty requireSprint = new BooleanProperty("Require Sprint", true);
    public final BooleanProperty checkFood = new BooleanProperty("Check Food Level", true);
    public final BooleanProperty checkBlindness = new BooleanProperty("Check Blindness", true);

    /**
     * Checks if WTap can be triggered based on player state.
     */
    private boolean canTrigger() {
        // Must be moving forward sufficiently
        if (mc.thePlayer.movementInput.moveForward < 0.8F) {
            return false;
        }

        // Not against a wall
        if (mc.thePlayer.isCollidedHorizontally) {
            return false;
        }

        // Food level check (if enabled)
        if (this.checkFood.getValue() && !mc.thePlayer.capabilities.allowFlying && mc.thePlayer.getFoodStats().getFoodLevel() <= 6.0F) {
            return false;
        }

        // Sprinting check (if enabled)
        if (this.requireSprint.getValue()) {
            if (!mc.thePlayer.isSprinting() && (mc.thePlayer.isUsingItem() || (this.checkBlindness.getValue() && mc.thePlayer.isPotionActive(Potion.blindness)) || !mc.gameSettings.keyBindSprint.isKeyDown())) {
                return false;
            }
        }

        return true;
    }

    public Wtap() {
        super("WTap", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || !this.active) {
            return;
        }

        // If conditions no longer met, deactivate and reset
        if (!this.canTrigger()) {
            this.deactivate();
            return;
        }

        // Handle delay phase
        if (this.delayTicks > 0L) {
            this.delayTicks -= 50L;  // Decrement by 50ms (assuming 20 TPS, 50ms per tick)
            return;
        }

        // Handle duration phase: stop forward movement
        if (this.durationTicks > 0L) {
            this.durationTicks -= 50L;
            this.stopForward = true;
            mc.thePlayer.movementInput.moveForward = 0.0F;
        } else {
            // Duration over, deactivate
            this.deactivate();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
            if (packet.getAction() == Action.ATTACK && !this.active && this.timer.hasTimeElapsed((long) this.cooldownMs.getValue()) && this.canTrigger()) {
                this.activate();
            }
        }
    }

    /**
     * Activates WTap by setting timers and flags.
     */
    private void activate() {
        this.timer.reset();
        this.active = true;
        this.stopForward = false;
        this.delayTicks = (long) (50.0F * this.delay.getValue());  // Convert to ms (50ms per tick)
        this.durationTicks = (long) (50.0F * this.duration.getValue());
    }

    /**
     * Deactivates WTap and resets timers.
     */
    private void deactivate() {
        this.active = false;
        this.stopForward = false;
        this.delayTicks = 0L;
        this.durationTicks = 0L;
    }

    @Override
    public void onDisabled() {
        this.deactivate();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.active ? "Active" : "Inactive"};
    }
}
