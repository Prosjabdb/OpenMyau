package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.util.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;

import java.util.Objects;

public class AutoClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private long clickDelay = 0L;
    private long blockHitDelay = 0L;
    public final IntProperty minCPS = new IntProperty("Min CPS", 8, 1, 20);
    public final IntProperty maxCPS = new IntProperty("Max CPS", 12, 1, 20);
    public final BooleanProperty blockHit = new BooleanProperty("Block Hit", false);
    public final FloatProperty blockHitTicks = new FloatProperty("Block Hit Ticks", 1.5F, 1.0F, 20.0F);
    public final BooleanProperty weaponsOnly = new BooleanProperty("Weapons Only", true);
    public final BooleanProperty allowTools = new BooleanProperty("Allow Tools", false);
    public final BooleanProperty breakBlocks = new BooleanProperty("Break Blocks", true);
    public final FloatProperty range = new FloatProperty("Range", 3.0F, 3.0F, 8.0F);
    public final FloatProperty hitBoxVertical = new FloatProperty("Hit Box Vertical", 0.1F, 0.0F, 1.0F);
    public final FloatProperty hitBoxHorizontal = new FloatProperty("Hit Box Horizontal", 0.2F, 0.0F, 1.0F);
    public final BooleanProperty jitter = new BooleanProperty("Jitter", false);
    public final FloatProperty jitterStrength = new FloatProperty("Jitter Strength", 0.5F, 0.0F, 2.0F);

    private long getNextClickDelay() {
        long baseDelay = 1000L / RandomUtil.nextLong(this.minCPS.getValue(), this.maxCPS.getValue());
        return baseDelay + (long) (Math.random() * 50); // Add slight randomization
    }

    private long getBlockHitDelay() {
        return (long) (50.0F * this.blockHitTicks.getValue());
    }

    private boolean isBreakingBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    private boolean canClick() {
        if (this.weaponsOnly.getValue() && !ItemUtil.hasRawUnbreakingEnchant() && (!this.allowTools.getValue() || !ItemUtil.isHoldingTool())) {
            return false;
        }
        if (this.breakBlocks.getValue() && this.isBreakingBlock() && !this.hasValidTarget()) {
            GameType gameType = mc.playerController.getCurrentGameType();
            return gameType != GameType.SURVIVAL && gameType != GameType.CREATIVE;
        }
        return true;
    }

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        if (entityPlayer == null || entityPlayer == mc.thePlayer || entityPlayer == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        if (entityPlayer.deathTime > 0) {
            return false;
        }
        float borderSize = entityPlayer.getCollisionBorderSize();
        return RotationUtil.rayTrace(entityPlayer.getEntityBoundingBox().expand(
                borderSize + this.hitBoxHorizontal.getValue(),
                borderSize + this.hitBoxVertical.getValue(),
                borderSize + this.hitBoxHorizontal.getValue()
        ), mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, this.range.getValue()) != null;
    }

    private boolean hasValidTarget() {
        return mc.theWorld.loadedEntityList.stream()
                .filter(e -> e instanceof EntityPlayer)
                .map(e -> (EntityPlayer) e)
                .anyMatch(this::isValidTarget);
    }

    public AutoClicker() {
        super("AutoClicker", false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.currentScreen != null) {
            return;
        }
        if (this.clickDelay > 0L) {
            this.clickDelay -= 50L;
        }
        if (this.blockHitDelay > 0L) {
            this.blockHitDelay -= 50L;
        }
        if (!this.isEnabled() || !this.canClick() || !mc.gameSettings.keyBindAttack.isKeyDown()) {
            return;
        }
        if (!mc.thePlayer.isUsingItem()) {
            while (this.clickDelay <= 0L) {
                KeyBindUtil.updateKeyState(mc.gameSettings.keyBindAttack.getKeyCode());
                KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
                this.clickDelay += this.getNextClickDelay();
                if (this.jitter.getValue()) {
                    mc.thePlayer.rotationYaw += (float) (Math.random() * this.jitterStrength.getValue() - this.jitterStrength.getValue() / 2);
                    mc.thePlayer.rotationPitch += (float) (Math.random() * this.jitterStrength.getValue() - this.jitterStrength.getValue() / 2);
                }
            }
        }
        if (this.blockHit.getValue() && this.blockHitDelay <= 0L && mc.gameSettings.keyBindUseItem.isKeyDown() && ItemUtil.isHoldingSword()) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            if (!mc.thePlayer.isUsingItem()) {
                KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindUseItem.getKeyCode());
                this.blockHitDelay += this.getBlockHitDelay();
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onClick(LeftClickMouseEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            this.clickDelay += this.getNextClickDelay();
        }
    }

    @Override
    public void onEnabled() {
        this.clickDelay = 0L;
        this.blockHitDelay = 0L;
    }

    @Override
    public void verifyValue(String mode) {
        if ("Min CPS".equals(mode)) {
            if (this.minCPS.getValue() > this.maxCPS.getValue()) {
                this.maxCPS.setValue(this.minCPS.getValue());
            }
        } else if ("Max CPS".equals(mode) && this.minCPS.getValue() > this.maxCPS.getValue()) {
            this.minCPS.setValue(this.maxCPS.getValue());
        }
    }

    @Override
    public String[] getSuffix() {
        return Objects.equals(this.minCPS.getValue(), this.maxCPS.getValue())
                ? new String[]{this.minCPS.getValue().toString()}
                : new String[]{String.format("%d-%d", this.minCPS.getValue(), this.maxCPS.getValue())};
    }
}
