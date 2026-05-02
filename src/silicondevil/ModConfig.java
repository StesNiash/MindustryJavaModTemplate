package silicondevil;

import arc.*;
import mindustry.ui.dialogs.SettingsMenuDialog;

public class ModConfig {
    private static final String PREFIX = "sd-";

    public static int maxBlocksPerProcessor() {
        return Core.settings.getInt(PREFIX + "max-blocks", 20);
    }

    public static float scanInterval() {
        return Core.settings.getInt(PREFIX + "scan-interval", 5);
    }

    public static float configInterval() {
        return Core.settings.getInt(PREFIX + "config-interval", 40) / 100f;
    }

    public static long processorUpdateCooldownMs() {
        return Core.settings.getInt(PREFIX + "update-cooldown", 250);
    }

    public static String targetString() {
        return "print \"SILICONDEVIL UNIT BUILD MOD\"";
    }

    public static String codePrefix() {
        return targetString() + "\n"
            + "sensor uDead myUnit @dead\n"
            + "jump 9 equal uDead 0\n"
            + "ubind @mega\n"
            + "sensor uCont @unit @controller\n"
            + "jump 8 equal uCont @this\n"
            + "jump 8 equal uCont processor1\n"
            + "end\n"
            + "set myUnit @unit\n"
            + "ubind myUnit\n"
            + "set @counter current\n"
            + "set current @counter";
    }

    public static void registerSettings(SettingsMenuDialog dialog) {
        dialog.addCategory("SiliconDevil", "icon-silicondevil", table -> {
            table.sliderPref(PREFIX + "max-blocks", 20, 1, 50, v -> v + " blocks");
            table.sliderPref(PREFIX + "scan-interval", 5, 1, 30, v -> v + "s");
            table.sliderPref(PREFIX + "config-interval", 40, 10, 200, v -> (v / 10f) + "s");
            table.sliderPref(PREFIX + "update-cooldown", 250, 50, 2000, v -> v + "ms");
        });
    }
}
