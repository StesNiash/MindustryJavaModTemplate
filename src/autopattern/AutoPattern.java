package autopattern;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

public class AutoPattern extends Mod {

    private Schematic pattern;
    private boolean enabled;
    private ImageButton toggleButton;
    private BaseDialog dialog;
    private Label statusLabel;
    private Table previewTable;
    private CheckBox enableBox;
    private boolean instantPlacement = true;

    private float dragStartX, dragStartY;
    private Tile dragStartTile;
    private boolean dragging;
    private float dragCurX, dragCurY;
    private boolean wasMouseDown;
    private Label hudStatusLabel;
    private static final float MIN_DRAG = 20f;

    private static final String PFX = "autopattern-";

    public AutoPattern() {
        Log.info("[AutoPattern] Constructor.");
    }

    @Override
    public void init() {
        loadSettings();
        buildDialog();
        loadSavedPattern();
        registerKeybind();
        registerDrawHook();
        buildHudButton();
        buildStatusLabel();
        addSettings();
    }

    private void loadSettings() {
        Core.settings.defaults(PFX + "offset-x", 0);
        Core.settings.defaults(PFX + "offset-y", 0);
        Core.settings.defaults(PFX + "instant", true);
        Core.settings.defaults(PFX + "pattern", "");
        Core.settings.defaults(PFX + "show-button", true);
        Core.settings.defaults(PFX + "activation-key",
            KeyCode.h.name().toUpperCase());
        Core.settings.defaults(PFX + "max-ore-tiles", 500);
        Core.settings.defaults(PFX + "min-ore-tiles", 1);
        instantPlacement = Core.settings.getBool(PFX + "instant");
    }

    private void loadSavedPattern() {
        String b64 = Core.settings.getString(PFX + "pattern");
        if (b64 != null && !b64.isEmpty()) {
            try {
                pattern = Schematics.readBase64(b64);
            } catch (Exception ignored) {
                Core.settings.put(PFX + "pattern", "");
            }
        }
        Time.runTask(1f, this::updateStatus);
    }

    private void buildDialog() {
        dialog = new BaseDialog("@autopattern.title");
        dialog.addCloseButton();

        dialog.cont.table(t -> {
            statusLabel = t.add("@autopattern.nopattern").pad(8).get();
            t.row();

            t.table(p -> {
                previewTable = new Table();
                p.add(previewTable).pad(8);
            }).row();

            t.table(buttons -> {
                buttons.button("@autopattern.clipboard", Icon.paste,
                    () -> loadFromClipboard()).pad(4).growX();
                buttons.button("@autopattern.fromfile", Icon.folder,
                    () -> showSchematicList()).pad(4).growX();
            }).growX().pad(4).row();

            t.table(offsets -> {
                offsets.add("@autopattern.offsetx").padRight(4);

                TextField oxField = new TextField(
                    String.valueOf(Core.settings.getInt(PFX + "offset-x")));
                oxField.setFilter(TextFieldFilter.digitsOnly);
                oxField.changed(() -> {
                    try {
                        Core.settings.put(PFX + "offset-x",
                            Integer.parseInt(oxField.getText()));
                    } catch (NumberFormatException ignored) {}
                });
                offsets.add(oxField).width(60).padRight(12);

                offsets.add("@autopattern.offsety").padRight(4);

                TextField oyField = new TextField(
                    String.valueOf(Core.settings.getInt(PFX + "offset-y")));
                oyField.setFilter(TextFieldFilter.digitsOnly);
                oyField.changed(() -> {
                    try {
                        Core.settings.put(PFX + "offset-y",
                            Integer.parseInt(oyField.getText()));
                    } catch (NumberFormatException ignored) {}
                });
                offsets.add(oyField).width(60);
            }).pad(4).row();

            t.label(() -> "@autopattern.offsethelp").pad(4).row();

            t.table(mo -> {
                mo.add("@autopattern.minoretiles").padRight(4);

                TextField moField = new TextField(
                    String.valueOf(Core.settings.getInt(PFX + "min-ore-tiles")));
                moField.setFilter(TextFieldFilter.digitsOnly);
                moField.changed(() -> {
                    try {
                        Core.settings.put(PFX + "min-ore-tiles",
                            Integer.parseInt(moField.getText()));
                    } catch (NumberFormatException ignored) {}
                });
                mo.add(moField).width(60);
            }).pad(4).row();

            t.table(m -> {
                m.add("@autopattern.mode").padRight(4);

                CheckBox box = new CheckBox("");
                box.setChecked(instantPlacement);
                box.changed(() -> {
                    instantPlacement = box.isChecked();
                    Core.settings.put(PFX + "instant", instantPlacement);
                });
                m.add(box).padRight(4);
                m.add("@autopattern.instant");
            }).pad(4).row();

            t.table(toggle -> {
                toggle.add("@autopattern.toggle").padRight(4);

                enableBox = new CheckBox("@autopattern.enable");
                enableBox.setChecked(enabled);
                enableBox.changed(() -> enabled = enableBox.isChecked());
                toggle.add(enableBox).padRight(4);
            }).pad(4).row();
        });

        dialog.shown(this::updateStatus);
    }

    private void buildHudButton() {
        Vars.ui.hudGroup.fill(table -> {
            toggleButton = table.button(Icon.grid, Styles.emptyTogglei, () -> {
                enabled = !enabled;
                if (dialog != null) dialog.show();
            }).get();

            toggleButton.resizeImage(30f);
            toggleButton.visible(
                () -> Core.settings.getBool(PFX + "show-button"));

            table.margin(5f);
            table.marginRight(155f);
            table.top().right();
        });
    }

    private void addSettings() {
        Vars.ui.settings.getCategories().add(
            new SettingsMenuDialog.SettingsCategory(
                Core.bundle.get("autopattern.settings.title"),
                Icon.grid,
                table -> {
                    table.checkPref(PFX + "show-button", true);

                    table.textPref(PFX + "activation-key",
                        KeyCode.h.name().toUpperCase());

                    table.sliderPref(PFX + "offset-x", 0, 0, 16, 1,
                        i -> Core.bundle.format("autopattern.settings.offsetx", i));
                    table.sliderPref(PFX + "offset-y", 0, 0, 16, 1,
                        i -> Core.bundle.format("autopattern.settings.offsety", i));

                    table.sliderPref(PFX + "max-ore-tiles", 500, 50, 5000, 50,
                        i -> Core.bundle.format("autopattern.settings.maxtiles", i));

                    table.sliderPref(PFX + "min-ore-tiles", 1, 1, 50, 1,
                        i -> Core.bundle.format("autopattern.settings.minoretiles", i));

                    table.checkPref(PFX + "instant", true,
                        v -> instantPlacement = v);
                }
            ));
    }

    private void registerKeybind() {
        Core.scene.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode keycode){
                if (Vars.state.isMenu()) return false;
                if (Vars.ui.chatfrag.shown()) return false;
                if (Core.scene.hasKeyboard()) return false;

                if (Core.input.ctrl() && Core.input.shift()
                        && keycode == KeyCode.c) {
                    String log = PatternTiler.logBuf.toString();
                    if (log.isEmpty()) {
                        Vars.ui.showInfo("Auto Pattern: log empty");
                    } else {
                        Core.app.setClipboardText(log);
                        Vars.ui.showInfo("Auto Pattern: log copied");
                        PatternTiler.clearLog();
                    }
                    return true;
                }

                String configured =
                    Core.settings.getString(PFX + "activation-key");
                if (configured.equalsIgnoreCase(keycode.name())){
                    enabled = !enabled;
                    return true;
                }
                return false;
            }
        });
    }

    private void registerDrawHook(){
        Events.run(Trigger.draw, () -> {
            boolean mouseDown = Core.input.isTouched();

            if (mouseDown && !wasMouseDown
                    && enabled && pattern != null
                    && !Vars.state.isMenu()
                    && !Core.scene.hasMouse()){
                float wx = Core.input.mouseWorldX();
                float wy = Core.input.mouseWorldY();
                Tile tile = Vars.world.tileWorld(wx, wy);
                if (tile != null
                        && tile.overlay().itemDrop != null){
                    dragStartX = wx;
                    dragStartY = wy;
                    dragCurX = wx;
                    dragCurY = wy;
                    dragStartTile = tile;
                    dragging = true;
                }
            }

            if (dragging && mouseDown){
                dragCurX = Core.input.mouseWorldX();
                dragCurY = Core.input.mouseWorldY();
            }

            if (dragging && !mouseDown && wasMouseDown){
                dragging = false;
                doPlacement();
            }

            if (dragging){
                float endX = dragCurX;
                float endY = dragCurY;
                float dx = endX - dragStartX;
                float dy = endY - dragStartY;
                int rot = Math.abs(dx) + Math.abs(dy) < MIN_DRAG
                    ? 0 : dirToRotations(dx, dy);

                Draw.z(Layer.overlayUI);
                Draw.color(Pal.accent);

                Lines.stroke(2f);
                Lines.line(dragStartX, dragStartY, endX, endY);

                Fill.circle(dragStartX, dragStartY, 6f);

                float ang = rot == 0 ? 90f : rot == 1 ? 180f
                    : rot == 2 ? 270f : 0f;
                float arrowLen = 10f;
                float ax = endX + Mathf.cosDeg(ang) * arrowLen;
                float ay = endY + Mathf.sinDeg(ang) * arrowLen;
                Lines.line(endX, endY, ax, ay);
            }

            wasMouseDown = mouseDown;
        });
    }

    private void doPlacement(){
        float dx = dragCurX - dragStartX;
        float dy = dragCurY - dragStartY;
        float dist = (float)Math.sqrt(dx * dx + dy * dy);

        int rotations = dist < MIN_DRAG
            ? 0 : dirToRotations(dx, dy);

        Schematic rotated = pattern;
        if (rotations != 0){
            rotated = Schematics.rotate(pattern, rotations);
        }

        Seq<Tile> oreTiles =
            PatternTiler.getConnectedOreTiles(dragStartTile,
                Core.settings.getInt(PFX + "max-ore-tiles"));
        if (oreTiles.isEmpty()) return;

        int ox = Core.settings.getInt(PFX + "offset-x");
        int oy = Core.settings.getInt(PFX + "offset-y");

        if (rotations % 2 != 0){
            int tmp = ox;
            ox = oy;
            oy = tmp;
        }

        int minOreTiles = Core.settings.getInt(PFX + "min-ore-tiles");

        PatternTiler.tile(rotated, oreTiles, ox, oy,
            minOreTiles, instantPlacement);

        Block bridgeBlock = PatternTiler.findBridgeBlock(rotated);
        Tile targetTile = Vars.world.tileWorld(dragCurX, dragCurY);
        if (bridgeBlock != null && targetTile != null) {
            PatternTiler.connectExits(PatternTiler.allExits,
                targetTile.x, targetTile.y,
                instantPlacement, bridgeBlock, Vars.player.team());
        }

        int count = PatternTiler.countPlacements(
            rotated, oreTiles, ox, oy);
        Vars.ui.showInfo(
            Core.bundle.format("autopattern.placed", count));

        enabled = false;
    }

    private static int dirToRotations(float dx, float dy){
        if (Math.abs(dx) > Math.abs(dy)){
            return dx > 0 ? 3 : 1;
        }else{
            return dy > 0 ? 0 : 2;
        }
    }

    private void buildStatusLabel(){
        Vars.ui.hudGroup.fill(table -> {
            hudStatusLabel = table.add("").get();
            table.top().left();
            table.margin(5f);
            table.marginLeft(5f);
        });

        hudStatusLabel.update(() -> {
            hudStatusLabel.setText(
                enabled ? "[accent]Auto Pattern: ON[]" : "");
        });
    }

    private void loadFromClipboard() {
        String text = Core.app.getClipboardText();
        if (text == null || text.isEmpty()) {
            Vars.ui.showInfo("@autopattern.clipboardempty");
            return;
        }
        try {
            pattern = Schematics.readBase64(text);
            Core.settings.put(PFX + "pattern", text);
            updateStatus();
            Vars.ui.showInfo("@autopattern.loaded");
        } catch (Exception ex) {
            Vars.ui.showInfo("@autopattern.invalid");
        }
    }

    private void showSchematicList() {
        Seq<Schematic> schems = Vars.schematics.all();
        if (schems.isEmpty()) {
            Vars.ui.showInfo("@autopattern.noschematics");
            return;
        }

        BaseDialog listDlg = new BaseDialog(
            "@autopattern.chooseschematic");
        listDlg.addCloseButton();

        listDlg.cont.pane(t -> {
            for (Schematic s : schems) {
                t.button(s.name(), () -> {
                    pattern = s;
                    try {
                        Core.settings.put(PFX + "pattern",
                            Vars.schematics.writeBase64(s));
                    } catch (Exception ignored) {}
                    updateStatus();
                    listDlg.hide();
                    Vars.ui.showInfo("@autopattern.loaded");
                }).growX().pad(2).row();
            }
        }).grow();

        listDlg.show();
    }

    private void updateStatus() {
        if (enableBox != null) enableBox.setChecked(enabled);

        if (pattern == null) {
            if (statusLabel != null) statusLabel.setText("@autopattern.nopattern");
            if (previewTable != null) previewTable.clear();
            return;
        }

        int blocks = 0;
        for (Schematic.Stile st : pattern.tiles) {
            if (st.block.synthetic()) blocks++;
        }

        if (statusLabel != null) {
            statusLabel.setText(Core.bundle.format("autopattern.status",
                pattern.width, pattern.height, blocks));
        }

        buildPreviewTable();
    }

    private void buildPreviewTable() {
        if (previewTable == null) return;
        previewTable.clear();
        if (pattern == null) return;

        int pw = pattern.width;
        int ph = pattern.height;
        int maxDim = Math.max(pw, ph);
        int cs = Math.max(4, Math.min(16, 160 / maxDim));

        ObjectMap<Long, Schematic.Stile> map = new ObjectMap<>();
        for (Schematic.Stile st : pattern.tiles) {
            map.put(((long)st.x << 32) | (st.y & 0xFFFF_FFFFL), st);
        }

        TextureRegion white = Core.atlas.find("white");
        TextureRegionDrawable whiteDraw =
            new TextureRegionDrawable(white);

        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                Schematic.Stile st = map.get(
                    ((long)x << 32) | (y & 0xFFFF_FFFFL));
                Image img = new Image(whiteDraw);
                img.setColor(st != null && st.block.synthetic()
                    ? Pal.accent : Pal.darkestGray);
                img.setScaling(Scaling.fit);
                previewTable.add(img).size(cs);
            }
            previewTable.row();
        }
    }
}
