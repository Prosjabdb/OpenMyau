package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.RandomUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

public class AutoClicker extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private long clickDelay;
    private int hitTickTimer = 0;

    public final IntProperty minCPS = new IntProperty("min-cps", 14, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-cps", 16, 1, 20);

    public final BooleanProperty timedHit = new BooleanProperty("timed-hit", false);
    public final IntProperty hitDelayTicks = new IntProperty("hit-delay-ticks", 4, 1, 20, timedHit::getValue);

    public final IntProperty timedMinCPS = new IntProperty("timed-min-cps", 6, 1, 20, timedHit::getValue);
    public final IntProperty timedMaxCPS = new IntProperty("timed-max-cps", 10, 1, 20, timedHit::getValue);

    public AutoClicker() {
        super("AutoClicker", false);
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (player == mc.thePlayer || player.isDead) return false;
        float eyeHeight = player.getEyeHeight();
        return RotationUtil.rayTrace(
                player.getEntityBoundingBox().expand(0.2, eyeHeight + 0.1, 0.2),
                mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch,
                3.0F
        ) != null;
    }

    private EntityPlayer getClosestTarget() {
        return mc.theWorld.playerEntities.stream()
                .filter(this::isValidTarget)
                .min((a, b) -> Float.compare(
                        mc.thePlayer.getDistanceToEntity(a),
                        mc.thePlayer.getDistanceToEntity(b)
                )).orElse(null);
    }

    private long getNextClickDelay() {
        boolean timed = timedHit.getValue() && hitTickTimer > 0;
        int min = timed ? timedMinCPS.getValue() : minCPS.getValue();
        int max = timed ? timedMaxCPS.getValue() : maxCPS.getValue();
        return 1000L / RandomUtil.nextLong(min, max);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) return;

        if (clickDelay > 0) clickDelay -= 50;

        if (mc.currentScreen != null) {
            return;
        }

        EntityPlayer target = getClosestTarget();

        if (timedHit.getValue() && target != null) {
            float dist = mc.thePlayer.getDistanceToEntity(target);
            if (dist <= 3.0F) hitTickTimer = hitDelayTicks.getValue();
        }

        if (hitTickTimer > 0) hitTickTimer--;

        if (!isEnabled()) return;
        if (!mc.gameSettings.keyBindAttack.isKeyDown()) return;
        if (mc.thePlayer.isUsingItem()) return;

        while (clickDelay <= 0) {
            clickDelay += getNextClickDelay();

            // Attack the closest target if exists
            if (target != null) {
                mc.playerController.attackEntity(mc.thePlayer, target);
            }

            // Swing hand visually
            mc.thePlayer.swingItem();
        }
    }

    @Override
    public void onEnabled() {
        clickDelay = 0;
        hitTickTimer = 0;
    }

    @Override
    public String[] getSuffix() {
        return minCPS.getValue().equals(maxCPS.getValue())
                ? new String[]{minCPS.getValue().toString()}
                : new String[]{String.format("%d-%d", minCPS.getValue(), maxCPS.getValue())};
    }
}
