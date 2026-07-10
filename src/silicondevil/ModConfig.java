package silicondevil;

import arc.*;
import mindustry.ui.dialogs.SettingsMenuDialog;

public class ModConfig {
    private static final String PREFIX = "sd-";

    public static int maxBlocksPerProcessor() {
        return Core.settings.getInt(PREFIX + "max-blocks", 120);
    }

    public static float scanInterval() {
        return Core.settings.getInt(PREFIX + "scan-interval", 5) * 60f;
    }

    public static float processorScanInterval() {
        return Core.settings.getInt(PREFIX + "processor-scan-interval", 30) * 60f;
    }

    public static float configCheckInterval() {
        return (Core.settings.getInt(PREFIX + "config-interval", 5) / 10f) * 60f;
    }

    public static long processorUpdateCooldownMs() {
        return Core.settings.getInt(PREFIX + "update-cooldown", 250);
    }

    public static String targetString() {
        return "print \"UB_2 Made by SiliconDevil\"";
    }

    public static String codePrefix() {
        return targetString() + "\n"
            + "ubind @mega\n"
            + "sensor uFlag @unit @flag\n"
            + "jump 6 notEqual uFlag 0\n"
            + "jump 15 equal dist_finish null\n"
            + "end\n"
            + "op idiv bx uFlag @maph\n"
            + "op mod by uFlag @maph\n"
            + "ucontrol move bx by 0 0 0\n"
            + "ucontrol getBlock bx by bt bb 0\n"
            + "jump 1 equal bb null\n"
            + "sensor bbmh bb @maxHealth\n"
            + "jump 1 equal bbmh 10\n"
            + "ucontrol flag 0 0 0 0 0\n"
            + "end\n"
            + "set @counter next_block";
    }

    public static void registerSettings(SettingsMenuDialog dialog) {
        dialog.addCategory("SiliconDevil", "icon-silicondevil", table -> {
            table.sliderPref(PREFIX + "max-blocks", 120, 1, 200, v -> v + " blocks");
            table.sliderPref(PREFIX + "scan-interval", 5, 1, 30, v -> v + "s");
            table.sliderPref(PREFIX + "processor-scan-interval", 30, 5, 120, v -> v + "s");
            table.sliderPref(PREFIX + "config-interval", 5, 1, 50, v -> (v / 10f) + "s");
            table.sliderPref(PREFIX + "update-cooldown", 250, 50, 2000, v -> v + "ms");
        });
    }
}
