// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package fail;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Log.LogLevel;
import fail.other.AutoBuild;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.mod.Mod;

public class FailMod extends Mod {
   public FailMod() {
      Log.logger.log(LogLevel.none, "[#00ffff]{Log}[#ffffff]FailMod успешно загружен!");
       Events.on(EventType.ClientLoadEvent.class, (var0) -> {
          AutoBuild.initKeybinds();
          AutoBuild.menu();
          AutoBuild.schematicsUI();
          Vars.ui.settings.addCategory("AutoBuild", table -> {
             table.checkPref("autobuild-enabled", true, b -> {
                AutoBuild.autobuildEnabled = b;
                Core.settings.put("autobuild-enabled", b);
             });
             table.checkPref("autobuild-opvp", false, b -> {
                AutoBuild.forOPVP = b;
                Core.settings.put("autobuild-opvp", b);
             });
          });
       });
   }

   public void init() {
   }
}
