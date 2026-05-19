// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package fail.other;

import arc.Core;
import arc.Events;
import arc.Graphics;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextField;
import arc.scene.ui.CheckBox;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.WidgetGroup;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Time;
import arc.util.Reflect;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.MappableContent;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.input.InputHandler;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog;
import mindustry.world.Block;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.units.UnitFactory;

public class AutoBuild {
   public static final BaseDialog listDialog = new BaseDialog("AutoBuild");
   public static final BaseDialog dialog = new BaseDialog("Build");
   public static final Seq<LogicGroup> logicGroups = new Seq();
   public static Schematic schematic;
   public static int index = 0;
    public static String saveName = "autoBuild";
    public static boolean forOPVP = false;
    public static final Seq<String> preAssignedTiles = new Seq<>();
    private static boolean hookInitialized = false;
    private static final Seq<String> processedPlacements = new Seq<>();
    private static int prevPlanCount = -1;

    static {
        forOPVP = Core.settings.getBool("autobuild-opvp", false);
    }

    public AutoBuild() {
   }

    public static void menu() {
       initPlacementHook();
       listDialog.addCloseButton();
      Vars.ui.menufrag.addButton("Auto build", Icon.hammer, () -> {
         showList();
      });
      dialog.closeOnBack(() -> {
         logicGroups.clear();
      });
      dialog.buttons.button("@back", Icon.left, () -> {
         logicGroups.clear();
         dialog.hide();
      }).size(210.0F, 64.0F);
      dialog.buttons.button("build", Icon.right, () -> {
         build();
         logicGroups.clear();
         dialog.hide();
      }).size(210.0F, 64.0F);
   }

   public static void showList() {
      Table var0 = listDialog.cont;
      var0.clear();
      int var1 = 0;
      int var2 = Math.max((int)((float)Core.graphics.getWidth() / Scl.scl(230.0F)), 1);
      Table var3 = new Table();
      Iterator var4 = Vars.schematics.all().iterator();

      while(var4.hasNext()) {
         final Schematic var5 = (Schematic)var4.next();
         SchematicsDialog.SchematicImage var6 = new SchematicsDialog.SchematicImage(var5);
         var6.setScaling(Scaling.fit);
         Stack var7 = new Stack();
         var7.addChild(var6);
         var7.addChild(new Label(var5.name(), Styles.outlineLabel));
         var7.addListener(new InputListener() {
            public boolean touchDown(InputEvent var1, float var2, float var3, int var4, KeyCode var5x) {
               if (var5x == KeyCode.mouseLeft) {
                  AutoBuild.schematic = var5;
                  AutoBuild.show();
                  AutoBuild.dialog.show();
                  return true;
               } else {
                  return false;
               }
            }
         });
         var3.add(var7).size(200.0F);
         ++var1;
         if (var1 % var2 == 0) {
            var3.row();
         }
      }

      var0.add(new ScrollPane(var3, Styles.defaultPane));
      listDialog.show();
   }

   public static void show() {
      Graphics var0 = Core.graphics;
      int var1 = schematic.width;
      int var2 = schematic.height;
      float var3 = Math.min(((float)var0.getWidth() * 0.8F - 128.0F) / (float)var1, (float)var0.getHeight() * 0.8F / (float)var2);
      Table var4 = dialog.cont;
       var4.clear();
       parseDescription();
       final LogicGroup var5 = logicGroups.size == 0 ? new LogicGroup((Schematic.Stile)null) : (LogicGroup)logicGroups.get(index);
      WidgetGroup var6 = new WidgetGroup();
      Iterator var7 = schematic.tiles.iterator();

      while(var7.hasNext()) {
         final Schematic.Stile var8 = (Schematic.Stile)var7.next();
         Block var9 = var8.block;
         int var10 = var9.size;
         final Image var11 = new Image(var9.fullIcon);
         var11.setBounds((float)(var8.x - (var10 - 1) / 2) * var3, (float)(var8.y - (var10 - 1) / 2) * var3, (float)var10 * var3, (float)var10 * var3);
         var11.touchable = Touchable.enabled;
         var11.setOrigin(1);
         var11.setRotation((float)(90 * var8.rotation));
          var11.setColor(var5.color(var8));
          if (!var5.has(var8) && preAssignedTiles.contains(var8.x + "," + var8.y)) {
             var11.setColor(Color.green);
          }
          var11.addListener(new InputListener() {
             public boolean touchDown(InputEvent var1, float var2, float var3, int var4, KeyCode var5x) {
                Color var6 = var11.color;
                if (var5x == KeyCode.mouseLeft) {
                   if (var5.has(var8)) {
                      String coord = var8.x + "," + var8.y;
                      var5.remove(var8);
                      var11.setColor(preAssignedTiles.contains(coord) ? Color.green : Color.white);
                   } else if (var6.equals(Color.red)) {
                      AutoBuild.logicGroups.remove(AutoBuild.index);
                      AutoBuild.index = 0;
                      Time.runTask(1.0F, () -> AutoBuild.show());
                   } else if (var6.equals(Color.purple) && var8.block == Blocks.microProcessor) {
                      for(int var7 = 0; var7 < AutoBuild.logicGroups.size; ++var7) {
                         if (((LogicGroup)AutoBuild.logicGroups.get(var7)).core == var8) {
                            AutoBuild.index = var7;
                            Time.runTask(1.0F, () -> AutoBuild.show());
                            return true;
                         }
                      }
                      AutoBuild.logicGroups.add(new LogicGroup(var8));
                      AutoBuild.index = AutoBuild.logicGroups.size - 1;
                      Time.runTask(1.0F, () -> AutoBuild.show());
                      return true;
                   } else {
                      if (var8.block == Blocks.microProcessor) {
                         for(int var7 = 0; var7 < AutoBuild.logicGroups.size; ++var7) {
                            if (((LogicGroup)AutoBuild.logicGroups.get(var7)).core == var8) {
                               AutoBuild.index = var7;
                               Time.runTask(1.0F, () -> AutoBuild.show());
                               return true;
                            }
                         }
                         AutoBuild.logicGroups.add(new LogicGroup(var8));
                         AutoBuild.index = AutoBuild.logicGroups.size - 1;
                         Time.runTask(1.0F, () -> AutoBuild.show());
                         return true;
                      }
                      var11.setColor(Color.green);
                      var5.add(var8);
                   }
                } else if (var5x == KeyCode.mouseRight && !var5.has(var8) && !preAssignedTiles.contains(var8.x + "," + var8.y)) {
                   var5.vec = var8;
                   Time.runTask(1.0F, () -> AutoBuild.show());
                }

                return false;
             }
          });
          var6.addChild(var11);
       }

       float var16 = (float)Core.settings.getInt("uiscale", 0) / 100.0F;
       var4.add(var6).size((float)var1 * var3 / var16, (float)var2 * var3 / var16);
       Table var17 = new Table();
       var17.setBackground(Tex.buttonSideRight);
       TextField var18 = new TextField(saveName, Styles.defaultField);
       var18.changed(() -> {
          saveName = var18.getText();
       });
       var17.add(var18).size(128.0F, 32.0F).row();
       
       // Add units field
       var17.add(new Label("Units:", Styles.outlineLabel)).size(128.0F, 24.0F).row();
       TextField unitsField = new TextField(String.valueOf(logicGroups.size > 0 ? ((LogicGroup)logicGroups.get(index)).units : 2), Styles.defaultField);
       unitsField.setFilter((textField, c) -> Character.isDigit(c));
       unitsField.changed(() -> {
          if (logicGroups.size > 0) {
             try {
                int value = Integer.parseInt(unitsField.getText());
                if (value >= 1 && value <= 10) {
                   ((LogicGroup)logicGroups.get(index)).units = value;
                }
             } catch (NumberFormatException e) {
                // Ignore invalid input
             }
          }
       });
       var17.add(unitsField).size(128.0F, 32.0F).row();
       
       // Add timeout field
       var17.add(new Label("Timeout (s):", Styles.outlineLabel)).size(128.0F, 24.0F).row();
       TextField timeoutField = new TextField(String.valueOf(logicGroups.size > 0 ? ((LogicGroup)logicGroups.get(index)).dropBuildAfterSeconds : 10), Styles.defaultField);
       timeoutField.setFilter((textField, c) -> Character.isDigit(c));
       timeoutField.changed(() -> {
          if (logicGroups.size > 0) {
             try {
                int value = Integer.parseInt(timeoutField.getText());
                if (value >= 5 && value <= 30000) {
                   ((LogicGroup)logicGroups.get(index)).dropBuildAfterSeconds = value;
                }
             } catch (NumberFormatException e) {
                // Ignore invalid input
             }
          }
       });
        var17.add(timeoutField).size(128.0F, 32.0F).row();
        
        // Add OPVP checkbox
        CheckBox opvpCheck = new CheckBox("For OPVP");
        opvpCheck.setChecked(forOPVP);
        opvpCheck.changed(() -> {
            forOPVP = opvpCheck.isChecked();
            Core.settings.put("autobuild-opvp", forOPVP);
        });
        var17.add(opvpCheck).size(128.0F, 32.0F).row();
        
        Table var19 = new Table();

       for(int var20 = 0; var20 < logicGroups.size; ++var20) {
          final int finalVar20 = var20;
          ImageButton var13 = new ImageButton(new TextureRegionDrawable(Blocks.microProcessor.uiIcon), Styles.clearTogglei);
          var13.setChecked(index == var20);
          var13.changed(() -> {
             if (index == finalVar20) {
                var13.setChecked(true);
             } else {
                index = finalVar20;
                show();
             }
          });
          var13.resizeImage(28.0F);
          var19.add(var13).size(32.0F).row();
       }

       var17.add(var19);
       var4.add(var17).size(128.0F, (float)var2 * var3 / var16);
   }

   public static void build() {
      Seq var0 = new Seq();
      Iterator var1 = logicGroups.iterator();

      while(true) {
         label43:
         while(var1.hasNext()) {
            LogicGroup var2 = (LogicGroup)var1.next();
            Schematic.Stile var3 = var2.core;
            var0.add(new Schematic.Stile(Blocks.microProcessor, var3.x, var3.y, LogicBlock.compress(var2.code(), var2.links()), (byte)0));
            Schematic.Stile var4 = var2.vec;
            Iterator var5 = logicGroups.iterator();

            while(var5.hasNext()) {
               LogicGroup var6 = (LogicGroup)var5.next();
               if (var6.core == var4) {
                  continue label43;
               }
            }

            var0.add(var4.copy());
         }

          var1 = schematic.tiles.iterator();

          while(true) {
             label31:
             while(var1.hasNext()) {
                Schematic.Stile var8 = (Schematic.Stile)var1.next();
                Iterator var10 = logicGroups.iterator();

                while(var10.hasNext()) {
                   LogicGroup var12 = (LogicGroup)var10.next();
                   if (var12.core == var8 || var12.vec == var8) {
                      continue label31;
                   }
                }

                var0.add(var8.copy());
             }

             Schematic var11 = new Schematic(var0, StringMap.of(new Object[]{"name", saveName}), schematic.width, schematic.height);
             var11.labels.add("autoBuild");
             var11.tags.put("description", "autobuild-v2:" + encodeSkipCoords());
             Vars.schematics.add(var11);
             return;
          }
      }
    }

    private static int[] stileToWorld(Schematic.Stile stile, int schemX, int schemY, int sWidth, int sHeight, int rotation, boolean flipped) {
        int S = stile.block.size;
        int planX = stile.x + schemX - sWidth / 2;
        int planY = stile.y + schemY - sHeight / 2;

        if (flipped) {
            planX = -planX + 2 * schemX - S;
        }

        for (int r = 0; r < rotation; r++) {
            int oldX = planX;
            planX = schemX + (planY - schemY);
            planY = schemY - (oldX - schemX) - S;
        }

        return new int[]{planX, planY};
    }

    public static void initPlacementHook() {
        if (hookInitialized) return;
        hookInitialized = true;
        Log.info("AutoBuild: initPlacementHook registered (Trigger.update)");

        Events.run(EventType.Trigger.update, () -> {
            if (Vars.player == null || Vars.player.unit() == null) return;

            InputHandler input = Vars.control.input;
            if (input == null) return;

            Queue<BuildPlan> unitPlans = Vars.player.unit().plans;
            if (unitPlans == null || unitPlans.isEmpty()) {
                processedPlacements.clear();
                prevPlanCount = 0;
                return;
            }

            int currentCount = unitPlans.size;
            if (prevPlanCount < 0) {
                prevPlanCount = currentCount;
                return;
            }
            int added = currentCount - prevPlanCount;
            if (added <= 0) {
                prevPlanCount = currentCount;
                return;
            }
            if (added < 3) {
                prevPlanCount = currentCount;
                return;
            }
            prevPlanCount = currentCount;

            Seq<BuildPlan> selectPlans;
            try {
                selectPlans = Reflect.get(InputHandler.class, input, "selectPlans");
            } catch (Exception e) { return; }
            if (selectPlans == null || selectPlans.isEmpty()) return;

            for (Schematic s : Vars.schematics.all()) {
                if (!s.labels.contains("autoBuild")) continue;
                String desc = s.tags.get("description", "");
                if (!desc.startsWith("autobuild-v2:")) continue;

                int schemX = -1, schemY = -1;
                int schemRotation = 0;
                boolean schemFlipped = false;

                outer:
                for (BuildPlan sp : selectPlans) {
                    if (sp.block == null || sp.block == Blocks.coreBastion) continue;
                    for (BuildPlan up : unitPlans) {
                        if (up.block != sp.block) continue;
                        if (up.x != sp.x || up.y != sp.y) continue;
                        for (Schematic.Stile stile : s.tiles) {
                            if (stile.block != sp.block) continue;
                            if (stile.block == Blocks.coreBastion) continue;
                            int S = stile.block.size;
                            int sx = stile.x, sy = stile.y;
                            for (int r = 0; r < 4; r++) {
                                for (int f = 0; f <= 1; f++) {
                                    boolean flipped = f == 1;
                                    int offsetX, offsetY;
                                    switch (r) {
                                        case 0:
                                            offsetX = flipped ? -sx + s.width / 2 - S : sx - s.width / 2;
                                            offsetY = sy - s.height / 2;
                                            break;
                                        case 1:
                                            offsetX = sy - s.height / 2;
                                            offsetY = flipped ? sx - s.width / 2 : -sx + s.width / 2 - S;
                                            break;
                                        case 2:
                                            offsetX = flipped ? sx - s.width / 2 : -sx + s.width / 2 - S;
                                            offsetY = -sy + s.height / 2 - S;
                                            break;
                                        case 3:
                                            offsetX = -sy + s.height / 2 - S;
                                            offsetY = flipped ? -sx + s.width / 2 - S : sx - s.width / 2;
                                            break;
                                        default:
                                            continue;
                                    }
                                    int candX = sp.x - offsetX;
                                    int candY = sp.y - offsetY;
                                    if (!verifyPlacement(s, unitPlans, candX, candY, r, flipped)) continue;
                                    String key = candX + "," + candY + "," + s.width + "," + s.height + "," + desc + "," + r + "," + f;
                                    if (processedPlacements.contains(key)) continue;
                                    schemX = candX;
                                    schemY = candY;
                                    schemRotation = r;
                                    schemFlipped = flipped;
                                    break outer;
                                }
                            }
                        }
                    }
                }

                if (schemX == -1) continue;

                processedPlacements.add(schemX + "," + schemY + "," + s.width + "," + s.height + "," + desc + "," + schemRotation + "," + (schemFlipped ? 1 : 0));

                Log.info("AutoBuild: autobuild schematic detected, schemX=" + schemX + " schemY=" + schemY + " w=" + s.width + " h=" + s.height + " rotation=" + schemRotation + " flipped=" + schemFlipped + " plansInQueue=" + unitPlans.size + " added=" + added);

                String skipData = desc.substring("autobuild-v2:".length());
                if (skipData.isEmpty()) continue;

                for (String coord : skipData.split(";")) {
                    if (coord.isEmpty()) continue;
                    String[] parts = coord.split(",");
                    if (parts.length < 2) continue;
                    int tileX = Integer.parseInt(parts[0].trim());
                    int tileY = Integer.parseInt(parts[1].trim());

                    for (Schematic.Stile stile : s.tiles) {
                        if (stile.x == tileX && stile.y == tileY) {
                            int[] worldPos = stileToWorld(stile, schemX, schemY, s.width, s.height, schemRotation, schemFlipped);
                            int worldX = worldPos[0];
                            int worldY = worldPos[1];

                            try {
                                Vars.player.unit().removeBuild(worldX, worldY, false);
                                Log.info("AutoBuild: removeBuild called at (" + worldX + "," + worldY + ")");

                                if (input != null) {
                                    Object planTree = Reflect.get(InputHandler.class, input, "playerPlanTree");
                                    if (planTree != null) {
                                        int phantomRotation = stile.rotation;
                                        if (schemFlipped && phantomRotation % 2 == 0) {
                                            phantomRotation = (phantomRotation + 2) % 4;
                                        }
                                        phantomRotation = (phantomRotation - schemRotation + 4) % 4;
                                        BuildPlan phantom = new BuildPlan(worldX, worldY, (byte)phantomRotation, stile.block, stile.config);
                                        try {
                                            Method insertMethod = null;
                                            for (Method m : planTree.getClass().getMethods()) {
                                                if (m.getName().equals("insert") && m.getParameterCount() == 1) {
                                                    insertMethod = m;
                                                    break;
                                                }
                                            }
                                            if (insertMethod != null) {
                                                insertMethod.invoke(planTree, phantom);
                                                Log.info("AutoBuild: phantom inserted at (" + worldX + "," + worldY + ")");
                                            }
                                        } catch (Exception e2) {
                                            Log.info("AutoBuild: phantom insert err at (" + worldX + "," + worldY + "): " + e2);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                Log.info("AutoBuild: removeBuild err at (" + worldX + "," + worldY + "): " + e);
                            }
                            break;
                        }
                    }
                }
            }
        });
    }

    private static boolean verifyPlacement(Schematic s, Queue<BuildPlan> plans, int schemX, int schemY) {
        return verifyPlacement(s, plans, schemX, schemY, 0, false);
    }

    private static boolean verifyPlacement(Schematic s, Queue<BuildPlan> plans, int schemX, int schemY, int rotation, boolean flipped) {
        int matches = 0, total = 0;
        for (Schematic.Stile stile : s.tiles) {
            if (stile.block == Blocks.coreBastion) continue;
            total++;
            int[] worldPos = stileToWorld(stile, schemX, schemY, s.width, s.height, rotation, flipped);
            int wx = worldPos[0], wy = worldPos[1];
            for (BuildPlan plan : plans) {
                if (plan.x == wx && plan.y == wy && plan.block == stile.block) {
                    matches++;
                    break;
                }
            }
        }
        return total > 0 && matches >= Math.max(3, total / 4);
    }

    private static void parseDescription() {
        preAssignedTiles.clear();
        if (schematic == null) return;
        String desc = schematic.tags.get("description", "");
        if (desc.isEmpty() || !desc.startsWith("autobuild-v2:")) return;

        String data = desc.substring("autobuild-v2:".length());
        if (data.isEmpty()) return;

        String[] coords = data.split(";");
        for (String coord : coords) {
            if (coord.isEmpty()) continue;
            preAssignedTiles.add(coord);
        }
    }

    private static String encodeSkipCoords() {
        StringBuilder sb = new StringBuilder();
        for (LogicGroup group : logicGroups) {
            for (CodeLink link : group.links) {
                sb.append(link.tileX).append(",").append(link.tileY).append(";");
            }
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static class LogicGroup {
      public static final int[][] sectors = new int[][]{{0, 1, 2, 3, 4, 5, 6, 7}, {1, 0, 3, 2, 5, 4, 7, 6}, {6, 3, 0, 5, 2, 7, 4, 1}, {3, 6, 5, 0, 7, 2, 1, 4}, {4, 5, 6, 7, 0, 1, 2, 3}, {5, 4, 7, 6, 1, 0, 3, 2}, {2, 7, 4, 1, 6, 3, 0, 5}, {7, 2, 1, 4, 3, 6, 5, 0}};
      public static final String[] vec8 = new String[]{"op add x @thisx offsetX\nop add y @thisy offsetY\nprint \"by \uf7e5=файл=\uf7bf\"\n", "op add x @thisx offsetY\nop add y @thisy offsetX\nop add rotation rotation 1\n", "op sub x @thisx offsetY\nop add y @thisy offsetX\nop add rotation rotation 1\n", "op sub x @thisx offsetX\nop add y @thisy offsetY\nprint \"by \uf7e5=файл=\uf7bf\"\n", "op sub x @thisx offsetX\nop sub y @thisy offsetY\nop add rotation rotation 2\n", "op sub x @thisx offsetY\nop sub y @thisy offsetX\nop add rotation rotation 3\n", "op add x @thisx offsetY\nop sub y @thisy offsetX\nop add rotation rotation 3\n", "op add x @thisx offsetX\nop sub y @thisy offsetY\nop add rotation rotation 2\n"};
      public final Schematic.Stile core;
      public final Seq<CodeLink> links = new Seq();
      public final Seq<LogicBlock.LogicLink> originalLinks = new Seq();
      public String originalCode = "";
      public Schematic.Stile vec;
      public int units = 2;
      public int dropBuildAfterSeconds = 10;

      public LogicGroup(Schematic.Stile processorTile) {
         this.core = processorTile;
         // Extract original links from processor config
         if (processorTile != null && processorTile.config instanceof byte[]) {
            extractOriginalLinks((byte[])processorTile.config);
         }
      }
      
       private void extractOriginalLinks(byte[] data) {
          if (data == null) return;
          try (java.io.DataInputStream stream = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(data)))) {
             stream.read(); // Version
             int bytelen = stream.readInt();
             if (bytelen > 1024 * 100) return;
             byte[] bytes = new byte[bytelen];
             stream.readFully(bytes);
             originalCode = new String(bytes, "UTF-8");
             
             int linkCount = stream.readInt();
             short coreX = this.core.x;
             short coreY = this.core.y;
             
             for (int i = 0; i < linkCount; i++) {
                String name = stream.readUTF();
                short x = stream.readShort();
                short y = stream.readShort();
                originalLinks.add(new LogicBlock.LogicLink(x, y, name, true));
             }
          } catch (Exception e) {
             // If extraction fails, just use empty list
          }
       }

      public Seq<LogicBlock.LogicLink> links() {
         Seq<LogicBlock.LogicLink> result = new Seq<>();
         short coreX = this.core.x;
         short coreY = this.core.y;
         
         // Add all original links
         for (LogicBlock.LogicLink link : originalLinks) {
            result.add(link);
         }
         
         // Check if anchor link already exists
         String anchorBaseName = LogicBlock.getLinkName(this.vec.block);
         int anchorRelX = this.vec.x - coreX;
         int anchorRelY = this.vec.y - coreY;
         
         boolean anchorExists = false;
         for (LogicBlock.LogicLink link : originalLinks) {
            if (link.x == anchorRelX && link.y == anchorRelY) {
               anchorExists = true;
               break;
            }
         }
         
         // Add anchor link only if it doesn't exist
         if (!anchorExists) {
            // Find next available number for this block type
            int maxNum = 0;
            for (LogicBlock.LogicLink link : result) {
               if (link.name.startsWith(anchorBaseName)) {
                  try {
                     int num = Integer.parseInt(link.name.substring(anchorBaseName.length()));
                     if (num > maxNum) maxNum = num;
                  } catch (NumberFormatException e) {
                     // Ignore invalid numbers
                  }
               }
            }
            result.add(new LogicBlock.LogicLink(anchorRelX, anchorRelY, anchorBaseName + (maxNum + 1), true));
         }
         
         return result;
      }
      
      // Get the actual anchor link name from the links list
      public String getAnchorLinkName() {
         short coreX = this.core.x;
         short coreY = this.core.y;
         int anchorRelX = this.vec.x - coreX;
         int anchorRelY = this.vec.y - coreY;
         
         Seq<LogicBlock.LogicLink> allLinks = links();
         for (LogicBlock.LogicLink link : allLinks) {
            if (link.x == anchorRelX && link.y == anchorRelY) {
               return link.name;
            }
         }
         
          // Fallback (should not happen)
          return LogicBlock.getLinkName(this.vec.block) + "1";
       }

       private int countInstructionLines(String code) {
          if (code == null || code.isEmpty()) return 0;
          String[] lines = code.split("\n");
          int count = 0;
          for (String line : lines) {
             String trimmed = line.trim();
             if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
             if (trimmed.endsWith(":")) continue; // label
             count++;
          }
          return count;
       }

        private String adjustJumpOffsets(String oldCode, int offset) {
           if (offset == 0 || oldCode == null || oldCode.isEmpty()) return oldCode;
           String[] lines = oldCode.split("\n");
           StringBuilder result = new StringBuilder();
           for (String line : lines) {
              // preserve leading whitespace
              int indentLen = 0;
              while (indentLen < line.length() && Character.isWhitespace(line.charAt(indentLen))) {
                 indentLen++;
              }
              String indent = line.substring(0, indentLen);
              String trimmed = line.substring(indentLen);
              if (trimmed.startsWith("jump ")) {
                 String[] parts = trimmed.split("\\s+");
                 if (parts.length >= 2) {
                    try {
                       int lineNum = Integer.parseInt(parts[1]);
                       parts[1] = String.valueOf(lineNum + offset);
                       // reconstruct trimmed line
                       StringBuilder newTrimmed = new StringBuilder();
                       newTrimmed.append(parts[0]);
                       for (int i = 1; i < parts.length; i++) {
                          newTrimmed.append(" ").append(parts[i]);
                       }
                       trimmed = newTrimmed.toString();
                    } catch (NumberFormatException e) {
                       // first argument is not a number, keep as is
                    }
                 }
              }
              result.append(indent).append(trimmed).append("\n");
           }
           return result.toString();
        }

       public String code() {
         // New algorithm based on Python implementation
         StringBuilder code = new StringBuilder();
         String anchorLinkName = getAnchorLinkName();
         
         short coreX = this.core.x;
         short coreY = this.core.y;
         boolean isEvenSize = this.vec.block.size % 2 == 0;
         float anchorX = isEvenSize ? (float)this.vec.x + 0.5F : (float)this.vec.x;
         float anchorY = isEvenSize ? (float)this.vec.y + 0.5F : (float)this.vec.y;
         
         // Calculate original sector
         float deltaX = anchorX - (float)coreX;
         float deltaY = anchorY - (float)coreY;
         float angle = Mathf.atan2(deltaX, deltaY) * 57.295776F;
         int originalSector = ((int)Math.floor(angle / 45.0) + 8) % 8;
         
         // Jump if build is completed
         code.append("jump EXTRA_CODE equal buildComplete 1\n");
         
         code.append("jump SELECT_UNIT notEqual uTotal null\n");

         // Check timeout
         // code.append("jump SELECT_UNIT greaterThanEq timeout @second\n");
         // code.append("jump CONSTANTS equal timeout null\n");
         // code.append("jump BUILD_COMPLETE always 0 0\n");
         
         // Constants
         code.append("CONSTANTS:\n");
         code.append("set uTotal ").append(this.units).append("\n");
         code.append("set ogSector ").append(originalSector).append("\n");
         code.append("set buildingRN 0\n");
         // code.append("set drop_build_after_n_seconds ").append(this.dropBuildAfterSeconds).append("\n");
         // code.append("op add timeout @second drop_build_after_n_seconds\n");
         
         // Check anchor
         code.append("CHECK_ANCHOR:\n");
         code.append("jump CHECK_ANCHOR equal ").append(anchorLinkName).append(" null\n");
         
         // Calculate new sector and jump
         code.append("sensor anchor_x ").append(anchorLinkName).append(" @x\n");
         code.append("sensor anchor_y ").append(anchorLinkName).append(" @y\n");
         code.append("set uCounter 0\n");
         code.append("op mul myFlag @thisx @maph\n");
         code.append("op add myFlag myFlag @thisy\n");
         code.append("op sub vector_x anchor_x @thisx\n");
         code.append("op sub vector_y anchor_y @thisy\n");
         code.append("op angle vector_angle vector_x vector_y\n");
         code.append("op idiv Sector vector_angle 45\n");
         code.append("op sub SectorDiff Sector ogSector\n");
         code.append("op mod WasFlipped SectorDiff 2\n");
         code.append("jump WAS_FLIPPED_JUMP equal WasFlipped 0\n");
         code.append("op add SectorDiff Sector ogSector\n");
         code.append("WAS_FLIPPED_JUMP:\n");
         code.append("op add SectorDiff SectorDiff 8\n");
         code.append("op mod SectorDiff SectorDiff 8\n");
         code.append("op mul jump SectorDiff 4\n");
         
         // Encode blocks info
         for(CodeLink link : this.links) {
            code.append("set bt ").append(link.codeName).append("\n");
            code.append("set offsetx ").append(link.x - (float)coreX).append("\n");
            code.append("set offsety ").append(link.y - (float)coreY).append("\n");
            code.append("set br ").append(link.rotation == -1 ? "null" : String.valueOf(link.rotation)).append("\n");
            code.append("set bc ").append(link.config.equals("0") ? "null" : link.config).append("\n");
            code.append("op add buildingRN buildingRN 1\n");
            code.append("op add Made_by_SiliconDevil @counter 1\n");
            code.append("jump CALCULATE_BX_BY always 0 0\n");
         }
         
         // Build complete check
         code.append("BUILD_COMPLETE:\n");
         code.append("jump SELECT_UNIT notEqual buildingRN 0\n");
         code.append("set buildComplete 1\n");
         
         // Unbind units
         for(int i = 1; i <= this.units; i++) {
            code.append("ubind u").append(i).append("\n");
            code.append("ucontrol flag 0 0 0 0 0\n");
            code.append("ucontrol unbind 0 0 0 0 0\n");
         }
         code.append("end\n");
         
         // Calculate build_x, build_y, block_rotation
         code.append("CALCULATE_BX_BY:\n");
         // If WasFlipped == 0: Change rotation
         code.append("jump BXBY_FLIPPED_JUMP equal WasFlipped 0\n");
         // If WasFlipped == 1: Change rotation
         code.append("op mod WFBR br 2\n");
         code.append("op mul WFBR2 WFBR 2\n");
         code.append("op add br br WFBR2\n");
         code.append("BXBY_FLIPPED_JUMP:\n");
         code.append("op add @counter @counter jump\n");
         // Sector 0
         code.append("op add bx @thisx offsetx\n");
         code.append("op add by @thisy offsety\n");
         code.append("op add br br 0\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 1
         code.append("op add bx @thisx offsety\n");
         code.append("op add by @thisy offsetx\n");
         code.append("op add br br 1\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 2
         code.append("op sub bx @thisx offsety\n");
         code.append("op add by @thisy offsetx\n");
         code.append("op add br br 1\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 3
         code.append("op sub bx @thisx offsetx\n");
         code.append("op add by @thisy offsety\n");
         code.append("op add br br 2\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 4
         code.append("op sub bx @thisx offsetx\n");
         code.append("op sub by @thisy offsety\n");
         code.append("op add br br 2\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 5
         code.append("op sub bx @thisx offsety\n");
         code.append("op sub by @thisy offsetx\n");
         code.append("op add br br 3\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 6
         code.append("op add bx @thisx offsety\n");
         code.append("op sub by @thisy offsetx\n");
         code.append("op add br br 3\n");
         code.append("jump DEDICATE_BLOCK always 0 0\n");
         // Sector 7
         code.append("op add bx @thisx offsetx\n");
         code.append("op sub by @thisy offsety\n");
         code.append("op add br br 0\n");

         // Dedicate block to units
         code.append("DEDICATE_BLOCK:\n");
         code.append("op mul wJump uCounter 6\n");
         code.append("op add @counter @counter wJump\n");
         for (int i = 1; i <= this.units; i++) {
            code.append("set ubt").append(i).append(" bt\n");
            code.append("set ubx").append(i).append(" bx\n");
            code.append("set uby").append(i).append(" by\n");
            code.append("set ubc").append(i).append(" bc\n");
            code.append("set ubr").append(i).append(" br\n");
            code.append("jump UNIT_CONTROL").append(i).append(" always 0 0\n");
         }
         
         // Select and control unit
         code.append("SELECT_UNIT:\n");
         code.append("op add uCounter uCounter 1\n");
         code.append("op mod uCounter uCounter uTotal\n");
         
          // IF "FOR OPVP" WE DO EXTRA CHECK FOR SHARD CORES, BECAUSE THEY BUILD AS VAULTS, BUT WE NEED TO CHECK FOR SHARDS TO UNBIND PROPERLY
          if (AutoBuild.forOPVP) {
             // YES OPVP
             code.append("op mul uJump uCounter 10\n"); // 1 extra line of code due to shard core check
             code.append("op add @counter @counter uJump\n");
             for(int i = 1; i <= this.units; i++) {
                code.append("UNIT_CONTROL").append(i).append(":\n");
                code.append("ubind u").append(i).append("\n");
                code.append("jump BIND_NEW_UNIT equal @unit null\n");
                code.append("UNIT_BUILD").append(i).append(":\n");
                code.append("ucontrol move ubx").append(i).append(" uby").append(i).append(" 0 0 0\n");
                code.append("ucontrol build ubx").append(i).append(" uby").append(i).append(" ubt").append(i).append(" ubr").append(i).append(" ubc").append(i).append("\n");
                code.append("ucontrol getBlock ubx").append(i).append(" uby").append(i).append(" gbt 0 0\n");
                code.append("jump SHARD_CORE_JUMP").append(i).append(" equal gbt @core-shard\n");
                code.append("jump SELECT_UNIT notEqual gbt ubt").append(i).append("\n");
                code.append("SHARD_CORE_JUMP").append(i).append(":\n");
                code.append("set ubt").append(i).append(" null\n");
                code.append("op sub buildingRN buildingRN 1\n");
                code.append("set @counter Made_by_SiliconDevil\n");
             }
          } else {
             // NO OPVP
             code.append("op mul uJump uCounter 9\n"); // 1 less line because we dont need to check for shard cores
             code.append("op add @counter @counter uJump\n");
             for(int i = 1; i <= this.units; i++) {
                code.append("UNIT_CONTROL").append(i).append(":\n");
                code.append("ubind u").append(i).append("\n");
                code.append("jump BIND_NEW_UNIT equal @unit null\n");
                code.append("UNIT_BUILD").append(i).append(":\n");
                code.append("ucontrol move ubx").append(i).append(" uby").append(i).append(" 0 0 0\n");
                code.append("ucontrol build ubx").append(i).append(" uby").append(i).append(" ubt").append(i).append(" ubr").append(i).append(" ubc").append(i).append("\n");
                code.append("ucontrol getBlock ubx").append(i).append(" uby").append(i).append(" gbt 0 0\n");
                code.append("jump SELECT_UNIT notEqual gbt ubt").append(i).append("\n");
                code.append("set ubt").append(i).append(" null\n");
                code.append("op sub buildingRN buildingRN 1\n");
                code.append("set @counter Made_by_SiliconDevil\n");
             }
          }
         
         // Bind new unit
         code.append("BIND_NEW_UNIT:\n");
         code.append("set bindTry 0\n");
         code.append("BINDTRY_LOOP:\n");
         code.append("ubind @mega\n");
         code.append("sensor uFlag @unit @flag\n");
         code.append("jump UNIT_FOUND equal uFlag 0\n");
         code.append("sensor uCont @unit @controller\n");
         code.append("jump UNIT_FOUND equal uCont @unit\n");
         code.append("op add bindTry bindTry 1\n");
         code.append("jump BINDTRY_LOOP lessThan bindTry 20\n");
         code.append("end\n");
         code.append("UNIT_FOUND:\n");
         code.append("ucontrol flag myFlag 0 0 0 0\n");
         code.append("op mul ubj uCounter 3\n");
         code.append("op add @counter @counter ubj\n");
         
         for(int i = 1; i <= this.units; i++) {
            code.append("set u").append(i).append(" @unit\n");
            code.append("jump UNIT_CONTROL").append(i).append(" notEqual ubt").append(i).append(" null\n");
            code.append("set @counter Made_by_SiliconDevil\n");
         }
         
          // POSTFIX CODE (if needed)
          code.append("EXTRA_CODE:\n");
          
          // Combine with original processor code
          String newCode = code.toString();
          int instructionCount = countInstructionLines(newCode);
          String adjustedOldCode = adjustJumpOffsets(originalCode, instructionCount);
          if (!adjustedOldCode.isEmpty()) {
              // Insert original code after EXTRA_CODE label
              String extraLabel = "EXTRA_CODE:\n";
              int idx = newCode.indexOf(extraLabel);
              if (idx >= 0) {
                  StringBuilder finalCode = new StringBuilder();
                  finalCode.append(newCode.substring(0, idx + extraLabel.length()));
                  finalCode.append(adjustedOldCode);
                  finalCode.append(newCode.substring(idx + extraLabel.length()));
                  return finalCode.toString();
              }
          }
          return newCode;
      }

      public Color color(Schematic.Stile var1) {
         if (var1 == this.core) {
            return Color.red;
         } else if (var1 == this.vec) {
            return Color.purple;
         } else {
            Iterator var2 = this.links.iterator();

            CodeLink var3;
            do {
               if (!var2.hasNext()) {
                  return Color.white;
               }

               var3 = (CodeLink)var2.next();
            } while(!var3.equals(var1));

            return Color.green;
         }
      }

      public void add(Schematic.Stile var1) {
         if (var1 != this.core && var1 != this.vec) {
            Iterator var2 = this.links.iterator();

            CodeLink var3;
            do {
               if (!var2.hasNext()) {
                  this.links.add(new CodeLink(var1));
                  return;
               }

               var3 = (CodeLink)var2.next();
            } while(!var3.equals(var1));

         }
      }

      public void remove(Schematic.Stile var1) {
         if (var1 != this.core && var1 != this.vec) {
            for(int var2 = 0; var2 < this.links.size; ++var2) {
               if (((CodeLink)this.links.get(var2)).equals(var1)) {
                  this.links.remove(var2);
                  return;
               }
            }

         }
      }

      public boolean has(Schematic.Stile var1) {
         if (var1 != this.core && var1 != this.vec) {
            Iterator var2 = this.links.iterator();

            CodeLink var3;
            do {
               if (!var2.hasNext()) {
                  return false;
               }

               var3 = (CodeLink)var2.next();
            } while(!var3.equals(var1));

            return true;
         } else {
            return true;
         }
      }

       public boolean electrolyzer() {
          Iterator var1 = this.links.iterator();

          CodeLink var2;
          do {
             if (!var1.hasNext()) {
                return false;
             }

             var2 = (CodeLink)var1.next();
          } while(var2.block != Blocks.electrolyzer);

          return true;
       }

       public String encodeLinks() {
          StringBuilder sb = new StringBuilder();
          for (CodeLink link : this.links) {
             sb.append(link.tileX).append(",");
             sb.append(link.tileY).append(",");
             sb.append(link.block.name).append(",");
             sb.append(link.rotation).append(",");
             sb.append(link.config.replace(",", "\\,")).append(";");
          }
          return sb.toString();
       }
    }

   public static class CodeLink {
      public final float x;
      public final float y;
      public final int tileX;
      public final int tileY;
      public final int rotation;
      public final Block block;
      public final String codeName;
      public final String name;
      public final String config;
      public final int length;

      public CodeLink(Schematic.Stile var1) {
         Block var2 = var1.block;
         this.block = var2 == Blocks.coreShard ? Blocks.vault : var2;
         this.name = LogicBlock.getLinkName(var2 == Blocks.vault ? Blocks.coreShard : var2);
         this.rotation = var2.rotate ? var1.rotation : -1;
         boolean var3 = var2.size % 2 == 0;
         this.x = var3 ? (float)var1.x + 0.5F : (float)var1.x;
         this.y = var3 ? (float)var1.y + 0.5F : (float)var1.y;
         this.codeName = "@" + this.block.name;
         this.config = this.config(var1.config, var2);
         this.tileX = var1.x;
         this.tileY = var1.y;
         this.length = 5 + (this.config.equals("0") ? 0 : 1) + (this.rotation == -1 ? 0 : 1);
      }

      public String config(Object var1, Block var2) {
         return var2.configurations.size > 0 && var1 == null ? "null" : (var1 instanceof MappableContent ? "@" + ((MappableContent)var1).name : (var2 instanceof UnitFactory ? ((Integer)var1 == -1 ? "null" : "@" + ((UnitFactory.UnitPlan)((UnitFactory)var2).plans.get((Integer)var1)).unit.name) : "0"));
      }

      public boolean equals(Schematic.Stile var1) {
         return this.tileX == var1.x && this.tileY == var1.y && (this.block == var1.block || this.block == Blocks.vault && var1.block == Blocks.coreShard);
      }
   }

   public static void schematicsUI() {
      Vars.ui.schematics.buttons.button("Auto build", Icon.hammer, () -> {
         showList();
      });
      SchematicsDialog.SchematicInfoDialog info = (SchematicsDialog.SchematicInfoDialog)Reflect.get(SchematicsDialog.class, Vars.ui.schematics, "info");
      info.shown(() -> {
         Label l = (Label)info.find((e) -> {
            if (e instanceof Label) {
               Label ll = (Label)e;
               if (ll.getText().toString().contains("[[" + Core.bundle.get("schematic") + "] ")) {
                  return true;
               }
            }
            return false;
         });
         if (l != null) {
            String schename = l.getText().toString().replace("[[" + Core.bundle.get("schematic") + "] ", "");
            Schematic sche = (Schematic)Vars.schematics.all().find((s) -> s.name().equals(schename));
            if (sche != null && sche.width <= Vars.maxSchematicSize && sche.height <= Vars.maxSchematicSize) {
               schematic = sche;
               info.buttons.row();
                info.buttons.button("Auto build", Icon.hammer, () -> {
                   schematic = sche;
                   logicGroups.clear();
                   index = 0;
                   show();
                   dialog.show();
                   info.hide();
                }).size(210.0F, 64.0F);
            }
         }
      });
   }
}
