package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.NumberProperty;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import java.awt.Robot;
import java.awt.event.InputEvent;
import net.minecraft.util.ChatComponentText;

public class AutoClicker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final NumberProperty clicksPerTick = new NumberProperty("Click Interval (ticks)", 20, 1, 100, 1);
    public final NumberProperty randomization = new NumberProperty("Randomization (ticks)", 5, 0, 20, 1);
    public final BooleanProperty disableInGUI = new BooleanProperty("Disable in GUI", true);
    public final BooleanProperty logClicks = new BooleanProperty("Log Clicks", false);

    private Robot robot;
    private int tickCount = 0;
    private int clickCount = 0;

    public AutoClicker() {
        super("AutoClicker", false);
        try {
            robot = new Robot();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) return;
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (disableInGUI.getValue() && mc.currentScreen != null) return;

        tickCount++;

        int effectiveInterval = clicksPerTick.getValue().intValue();

        // Add randomization
        if (randomization.getValue().intValue() > 0) {
            effectiveInterval += (int) (Math.random() * randomization.getValue().intValue());
        }

        if (tickCount >= effectiveInterval) {
            click();
            tickCount = 0;
        }
    }

    private void click() {
        if (robot == null) return;

        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        clickCount++;
        if (logClicks.getValue() && clickCount % 10 == 0) { // log every 10 clicks
            mc.thePlayer.addChatMessage(new ChatComponentText("AutoClicker: Clicked " + clickCount + " times"));
        }
    }

    @Override
    public void onDisabled() {
        tickCount = 0;
        clickCount = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{clicksPerTick.getValue().intValue() + " ticks"};
    }
}
