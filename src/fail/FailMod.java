// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package fail;

import arc.Events;
import arc.util.Log;
import arc.util.Log.LogLevel;
import fail.other.AutoBuild;
import mindustry.game.EventType;
import mindustry.mod.Mod;

public class FailMod extends Mod {
   public FailMod() {
      Log.logger.log(LogLevel.none, "[#00ffff]{Log}[#ffffff]FailMod успешно загружен!");
       Events.on(EventType.ClientLoadEvent.class, (var0) -> {
          AutoBuild.menu();
          AutoBuild.schematicsUI();
       });
   }

   public void init() {
   }
}
