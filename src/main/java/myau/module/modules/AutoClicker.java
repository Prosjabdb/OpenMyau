package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Objects;

public class AutoClicker extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private boolean clickPending = false;
    private long clickDelay = 0L;

    private boolean blockHitPending = false;
    private long blockHitDelay = 0L;

    // Normal CPS
    public final IntProperty minCPS = new IntProperty("min-cps", 14, 1, 20);
    public final IntProperty maxCPS = new IntProperty("max-cps", 16, 1, 20);

    // Timed Hits CPS
    public final IntProperty timedMinCPS = new IntProperty("timed-min-cps", 6, 1, 20);
    public final IntProperty timedMaxCPS = new IntProperty("timed-max-cps", 10, 1, 20);

    public final BooleanProperty blockHit = new BooleanProperty("block-hit", false);
    public final FloatProperty blockHitTicks =
            new FloatProperty("block-hit-ticks", 1.5F, 1.0F, 20.0F, blockHit::getValue);

    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools =
            new BooleanProperty("allow-tools", false, weaponsOnly::getValue);

    public final BooleanProperty breakBlocks = new BooleanProperty("break-blocks", true);
    public final FloatProperty range =
            new FloatProperty("range", 3.0F, 3.0F, 8.0F, breakBlocks::getValue);

    public final FloatProperty hitBoxVertical =
            new FloatProperty("hit-box-vertical", 0.1F, 0.0F, 1.0F, breakBlocks::getValue);
    public final FloatProperty hitBoxHorizontal =
            new FloatProperty("hit-box-horizontal", 0.2F, 0.0F, 1.0F, breakBlocks::getValue);

    // Timed Hits state
    private boolean timedHitsActive = false;
    private double lastTargetDistance = -1;

    public AutoClicker() {
        super("AutoClicker", false);
    }

    private long getNextClickDelay() {
        int min = minCPS.getValue();
        int max = maxCPS.getValue();

        if (timedHitsActive) {
            min = timedMinCPS.getValue();
            max = timedMaxCPS.getValue();
        }

        return 1000L / RandomUtil.nextLong(min, max);
    }

    private long getBlockHitDelay() {
        return (long) (50.0F * blockHitTicks.getValue());
    }

    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    private boolean canClick() {
        if (!weaponsOnly.getValue()
                || ItemUtil.hasRawUnbreakingEnchant()
                || (allowTools.getValue() && ItemUtil.isHoldingTool())) {

            if (breakBlocks.getValue() && isBreakingBlock() && !hasValidTarget()) {
                GameType gt = mc.playerController.getCurrentGameType();
                return gt != GameType.SURVIVAL && gt != GameType.CREATIVE;
            }
            return true;
        }
        return false;
    }

    private boolean isValidTarget(EntityPlayer p) {
        if (p == mc.thePlayer || p == mc.thePlayer.ridingEntity) return false;
        if (p.deathTime > 0) return false;

        float border = p.getCollisionBorderSize();
        return RotationUtil.rayTrace(
                p.getEntityBoundingBox().expand(
                        border + hitBoxHorizontal.getValue(),
                        border + hitBoxVertical.getValue(),
                        border + hitBoxHorizontal.getValue()
                )
