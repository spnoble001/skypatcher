package perfectpathc.dev.modules.impl.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import perfectpathc.dev.PerfectPatch;
import perfectpathc.dev.modules.impl.Module;
import perfectpathc.dev.modules.impl.Equipo;
import perfectpathc.dev.modules.impl.Refill;
import perfectpathc.dev.modules.impl.Settings;
import perfectpathc.dev.modules.impl.ThrowPot;
import perfectpathc.dev.modules.impl.utils.GlobalModuleNotifications;

/** Vertical tree-based combat click GUI. */
public final class ClickGuiObj extends GuiScreen {
    private static final int BACKGROUND = 0xE6141519;
    private static final int PANEL_BG = 0xF01C1E24;
    private static final int HEADER_BG = 0xFF23262E;
    private static final int HEADER_HOVER = 0xFF2A2E38;
    private static final int ROW_BG = 0xFF1A1C22;
    private static final int ROW_HOVER = 0xFF262933;
    private static final int ACCENT = 0xFF5B8CFF;
    private static final int ACCENT_SOFT_BG = 0x335B8CFF;
    private static final int ACCENT_SOFT_BG_HOVER = 0x445B8CFF;
    private static final int TEXT_PRIMARY = 0xFFEDEFF4;
    private static final int TEXT_MUTED = 0xFF8A8F9C;
    private static final int TEXT_ACCENT = 0xFF7FA6FF;
    private static final int SCROLLBAR_TRACK = 0x20FFFFFF;
    private static final int SCROLLBAR_THUMB = 0x60FFFFFF;

    private static final int LEFT_MARGIN = 12;
    private static final int PANEL_WIDTH = 300;
    private static final int HEADER_HEIGHT = 24;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_GAP = 3;
    private static final int MODULE_GAP = 6;
    private static final int TOP_MARGIN = 26;
    private static final int BOTTOM_MARGIN = 12;
    private static final int ARROW_ZONE_WIDTH = 22;
    private static final int CHILD_INDENT = 16;
    private static final int SLIDER_TRACK_WIDTH = 100;
    private static final int SLIDER_RIGHT_MARGIN = 12;
    private static final int SCROLLBAR_WIDTH = 4;

    private final List<Module> modules;
    private final Set<Module> expanded = new HashSet<Module>();
    private int scrollOffset;
    private Settings draggedSlider;
    private boolean draggedRangeUpper;
    private Settings listeningKeybind;
    private int draggedSliderX;
    private int draggedSliderWidth;

    public ClickGuiObj() {
        this((Module) PerfectPatch.instance.getModule(0),
                (Module) PerfectPatch.instance.getModule(1),
                (Module) PerfectPatch.instance.getModule(3),
                (Module) PerfectPatch.instance.getModule(9));
    }

    public static ClickGuiObj patchGui() {
        return new ClickGuiObj();
    }

    public static ClickGuiObj combatGui() {
        return new ClickGuiObj((Module) PerfectPatch.instance.getModule(4),
                (Module) PerfectPatch.instance.getModule(5),
                (Module) PerfectPatch.instance.getModule(7),
                (Module) PerfectPatch.instance.getModule(8),
                (Module) PerfectPatch.instance.getModule(6));
    }

    public ClickGuiObj(Module... modules) {
        this.modules = Arrays.asList(modules);
    }

    private enum RowType { MODULE_HEADER, CHILD_HEADER, SETTING, TEAM_MEMBER, TEAM_EMPTY }

    private static final class Row {
        private final RowType type;
        private final Module module;
        private final Settings setting;
        private final Equipo.MemberView teamMember;
        private final int indent;

        private Row(RowType type, Module module, Settings setting, int indent) {
            this(type, module, setting, null, indent);
        }

        private Row(RowType type, Module module, Settings setting,
                    Equipo.MemberView teamMember, int indent) {
            this.type = type;
            this.module = module;
            this.setting = setting;
            this.teamMember = teamMember;
            this.indent = indent;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, BACKGROUND);
        drawRect(0, 0, this.width, 18, HEADER_BG);
        this.fontRendererObj.drawStringWithShadow(
                (this.modules.size() == 2
                        || this.modules.contains(PerfectPatch.instance.getModule(7)))
                        ? "rguia" : "patch by nobleesky",
                6.0F, 6.0F, TEXT_PRIMARY);

        int panelX = LEFT_MARGIN;
        int viewportTop = TOP_MARGIN;
        int maxViewportHeight = this.height - TOP_MARGIN - BOTTOM_MARGIN;
        List<Row> rows = buildRows();
        int contentHeight = totalContentHeight(rows);
        int viewportHeight = Math.min(contentHeight, maxViewportHeight);
        clampScroll(contentHeight, viewportHeight);
        drawRect(panelX, viewportTop, panelX + PANEL_WIDTH,
                viewportTop + viewportHeight, PANEL_BG);

        beginScissor(panelX, viewportTop, PANEL_WIDTH, viewportHeight);
        int y = viewportTop - this.scrollOffset;
        Module lastModule = null;
        for (Row row : rows) {
            if (row.type == RowType.MODULE_HEADER && lastModule != null) {
                y += MODULE_GAP;
            }
            int rowHeight = rowHeight(row);
            if (y + rowHeight >= viewportTop && y <= viewportTop + viewportHeight) {
                drawRow(row, panelX, y, mouseX, mouseY);
            }
            y += rowHeight;
            if (row.type == RowType.MODULE_HEADER) lastModule = row.module;
        }
        endScissor();

        if (contentHeight > viewportHeight) {
            drawScrollbar(panelX, viewportTop, viewportHeight, contentHeight);
        }
        if (this.draggedSlider != null && Mouse.isButtonDown(0)) {
            if (this.draggedSlider.isRangeSetting()) {
                updateRangeSlider(this.draggedSlider, this.draggedRangeUpper,
                        mouseX, this.draggedSliderX, this.draggedSliderWidth);
            } else {
                updateSlider(this.draggedSlider, mouseX,
                        this.draggedSliderX, this.draggedSliderWidth);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        for (Module module : this.modules) {
            rows.add(new Row(RowType.MODULE_HEADER, module, null, 0));
            if (!this.expanded.contains(module)) continue;
            for (Settings setting : module.getSettings()) {
                rows.add(new Row(RowType.SETTING, module, setting, 1));
            }
            if (module instanceof Equipo) {
                List<Equipo.MemberView> members = ((Equipo) module).miembros();
                if (members.isEmpty()) {
                    rows.add(new Row(RowType.TEAM_EMPTY, module, null, 1));
                } else {
                    for (Equipo.MemberView member : members) {
                        rows.add(new Row(RowType.TEAM_MEMBER, module, null, member, 1));
                    }
                }
            }
            for (Module child : childModules(module)) {
                rows.add(new Row(RowType.CHILD_HEADER, child, null, 1));
                if (this.expanded.contains(child)) {
                    for (Settings setting : child.getSettings()) {
                        rows.add(new Row(RowType.SETTING, child, setting, 2));
                    }
                }
            }
        }
        return rows;
    }

    private static int rowHeight(Row row) {
        return (row.type == RowType.MODULE_HEADER || row.type == RowType.CHILD_HEADER
                ? HEADER_HEIGHT : ROW_HEIGHT) + ROW_GAP;
    }

    private int totalContentHeight(List<Row> rows) {
        int height = 0;
        Module lastModule = null;
        for (Row row : rows) {
            if (row.type == RowType.MODULE_HEADER && lastModule != null) height += MODULE_GAP;
            height += rowHeight(row);
            if (row.type == RowType.MODULE_HEADER) lastModule = row.module;
        }
        return height;
    }

    private void drawRow(Row row, int panelX, int y, int mouseX, int mouseY) {
        if (row.type == RowType.SETTING) drawSetting(row, panelX, y, mouseX, mouseY);
        else if (row.type == RowType.TEAM_MEMBER || row.type == RowType.TEAM_EMPTY) {
            drawTeamRow(row, panelX, y, mouseX, mouseY);
        } else drawHeader(row, panelX, y, mouseX, mouseY);
    }

    private void drawTeamRow(Row row, int panelX, int y, int mouseX, int mouseY) {
        int indentPx = row.indent * CHILD_INDENT;
        int x = panelX + indentPx + 6;
        int width = PANEL_WIDTH - indentPx - 12;
        boolean hovered = inside(mouseX, mouseY, x, y, width, ROW_HEIGHT);
        drawRect(x, y, x + width, y + ROW_HEIGHT, hovered ? ROW_HOVER : ROW_BG);
        if (row.type == RowType.TEAM_EMPTY) {
            this.fontRendererObj.drawString("No hay jugadores agregados",
                    x + 8, y + 7, TEXT_MUTED);
            return;
        }
        this.fontRendererObj.drawString(row.teamMember.getName(),
                x + 8, y + 7, TEXT_PRIMARY);
        int removeX = x + width - 24;
        boolean removeHovered = inside(mouseX, mouseY, removeX, y, 24, ROW_HEIGHT);
        if (removeHovered) drawRect(removeX, y, x + width, y + ROW_HEIGHT, 0x44FF4D5A);
        String remove = "x";
        this.fontRendererObj.drawStringWithShadow(remove,
                removeX + 12 - this.fontRendererObj.getStringWidth(remove) / 2,
                y + 7, removeHovered ? 0xFFFF6B76 : TEXT_MUTED);
    }

    private void drawHeader(Row row, int panelX, int y, int mouseX, int mouseY) {
        Module module = row.module;
        int indentPx = row.indent * CHILD_INDENT;
        int x = panelX + indentPx + 6;
        int width = PANEL_WIDTH - indentPx - 12;
        boolean hovered = inside(mouseX, mouseY, x, y, width, HEADER_HEIGHT);
        int bg = module.isEnabled()
                ? (hovered ? ACCENT_SOFT_BG_HOVER : ACCENT_SOFT_BG)
                : (hovered ? HEADER_HOVER : HEADER_BG);
        drawRect(x, y, x + width, y + HEADER_HEIGHT, bg);
        if (module.isEnabled()) drawRect(x, y, x + 3, y + HEADER_HEIGHT, ACCENT);
        String arrow = this.expanded.contains(module) ? "\u25BE" : "\u25B8";
        this.fontRendererObj.drawString(arrow, x + 7, y + 8, TEXT_MUTED);
        this.fontRendererObj.drawStringWithShadow(module.getName(),
                x + ARROW_ZONE_WIDTH, y + 8, TEXT_PRIMARY);
        String state = module.isEnabled() ? "ON" : "OFF";
        this.fontRendererObj.drawStringWithShadow(state,
                x + width - 7 - this.fontRendererObj.getStringWidth(state),
                y + 8, module.isEnabled() ? TEXT_ACCENT : TEXT_MUTED);
    }

    private void drawSetting(Row row, int panelX, int y, int mouseX, int mouseY) {
        Settings setting = row.setting;
        int indentPx = row.indent * CHILD_INDENT;
        int x = panelX + indentPx + 6;
        int width = PANEL_WIDTH - indentPx - 12;
        if (setting.isLabelSetting()) {
            drawRect(x, y, x + width, y + ROW_HEIGHT, 0xFF252832);
            this.fontRendererObj.drawStringWithShadow(
                    "--- " + setting.getName() + " ---",
                    x + 8, y + 7, TEXT_ACCENT);
            return;
        }
        boolean interactive = setting.getOwner().isSettingInteractive(setting);
        boolean hovered = interactive && inside(mouseX, mouseY, x, y, width, ROW_HEIGHT);
        drawRect(x, y, x + width, y + ROW_HEIGHT, hovered ? ROW_HOVER : ROW_BG);
        this.fontRendererObj.drawString(setting.getName(), x + 8, y + 7,
                interactive ? TEXT_MUTED : 0xFF555963);
        int sliderWidth = Math.max(1, Math.min(SLIDER_TRACK_WIDTH, width - 90));
        int sliderX = x + width - SLIDER_RIGHT_MARGIN - sliderWidth;

        if (setting.isKeybindSetting()) {
            String value = this.listeningKeybind == setting ? "PRESS..." : keyName(keybindValue(setting));
            drawRight(value, x, width, y + 7, this.listeningKeybind == setting ? TEXT_PRIMARY : TEXT_ACCENT);
        } else if (setting.isBooleanSetting()) {
            String value = setting.getValBoolean() ? "ON" : "OFF";
            drawRight(value, x, width, y + 7, interactive && setting.getValBoolean()
                    ? TEXT_ACCENT : TEXT_MUTED);
        } else if (setting.isModeSetting()) {
            drawRight(setting.getValString(), x, width, y + 7, TEXT_ACCENT);
        } else if (setting.isRangeSetting()) {
            float range = setting.getMax() - setting.getMin();
            float lowerRatio = range <= 0.0F ? 0.0F : (setting.getRangeMinimum() - setting.getMin()) / range;
            float upperRatio = range <= 0.0F ? 0.0F : (setting.getRangeMaximum() - setting.getMin()) / range;
            int lowerX = sliderX + Math.round(sliderWidth * lowerRatio);
            int upperX = sliderX + Math.round(sliderWidth * upperRatio);
            drawRect(sliderX, y + 15, sliderX + sliderWidth, y + 18, 0xFF2E323C);
            drawRect(lowerX, y + 15, upperX, y + 18, ACCENT);
            drawRect(lowerX - 1, y + 13, lowerX + 2, y + 20, TEXT_PRIMARY);
            drawRect(upperX - 1, y + 13, upperX + 2, y + 20, TEXT_PRIMARY);
            drawRight(formatRangeValue(setting), x, width, y - 1, TEXT_ACCENT);
        } else {
            float range = setting.getMax() - setting.getMin();
            float ratio = range <= 0.0F ? 0.0F : (setting.getValfloat() - setting.getMin()) / range;
            drawRect(sliderX, y + 15, sliderX + sliderWidth, y + 18, 0xFF2E323C);
            drawRect(sliderX, y + 15, sliderX + Math.round(sliderWidth * ratio), y + 18, ACCENT);
            drawRight(formatSettingValue(setting), x, width, y - 1, TEXT_ACCENT);
        }
    }

    private void drawRight(String text, int x, int width, int y, int color) {
        this.fontRendererObj.drawString(text,
                x + width - 8 - this.fontRendererObj.getStringWidth(text), y, color);
    }

    private static String formatSettingValue(Settings setting) {
        return String.format(setting.isIntegerSetting() ? "%.0f" : "%.2f", setting.getValfloat());
    }

    private static String formatRangeValue(Settings setting) {
        return String.format(setting.isIntegerSetting() ? "%.0f-%.0f" : "%.2f-%.2f",
                setting.getRangeMinimum(), setting.getRangeMaximum());
    }

    private void drawScrollbar(int panelX, int viewportTop, int viewportHeight, int contentHeight) {
        int trackX = panelX + PANEL_WIDTH - SCROLLBAR_WIDTH - 2;
        drawRect(trackX, viewportTop, trackX + SCROLLBAR_WIDTH,
                viewportTop + viewportHeight, SCROLLBAR_TRACK);
        int thumbHeight = Math.max(16, Math.round((float) viewportHeight * viewportHeight / contentHeight));
        int maxScroll = contentHeight - viewportHeight;
        float ratio = maxScroll <= 0 ? 0.0F : (float) this.scrollOffset / maxScroll;
        int thumbY = viewportTop + Math.round((viewportHeight - thumbHeight) * ratio);
        drawRect(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, SCROLLBAR_THUMB);
    }

    private void clampScroll(int contentHeight, int viewportHeight) {
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset));
    }

    private void beginScissor(int x, int y, int width, int height) {
        ScaledResolution resolution = new ScaledResolution(this.mc);
        int scale = resolution.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, this.mc.displayHeight - (y + height) * scale,
                width * scale, height * scale);
    }

    private void endScissor() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) this.scrollOffset -= Integer.signum(wheel) * (ROW_HEIGHT + ROW_GAP) * 2;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (this.listeningKeybind != null && acceptsMouseBinding(this.listeningKeybind)) {
            // Minecraft/LWJGL reserve button 0 for the click that opened this
            // row. Bind every available additional button as MOUSE:1..N.
            if (mouseButton >= 1 && mouseButton < Mouse.getButtonCount()) {
                setKeybind(this.listeningKeybind, -mouseButton);
                this.listeningKeybind = null;
            }
            return;
        }
        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        int panelX = LEFT_MARGIN;
        int viewportTop = TOP_MARGIN;
        int maxViewportHeight = this.height - TOP_MARGIN - BOTTOM_MARGIN;
        List<Row> rows = buildRows();
        int contentHeight = totalContentHeight(rows);
        int viewportHeight = Math.min(contentHeight, maxViewportHeight);
        if (!inside(mouseX, mouseY, panelX, viewportTop, PANEL_WIDTH, viewportHeight)) return;

        int y = viewportTop - this.scrollOffset;
        Module lastModule = null;
        for (Row row : rows) {
            if (row.type == RowType.MODULE_HEADER && lastModule != null) y += MODULE_GAP;
            int rowHeight = rowHeight(row);
            int indentPx = row.indent * CHILD_INDENT;
            int rowX = panelX + indentPx + 6;
            int rowWidth = PANEL_WIDTH - indentPx - 12;
            int visualHeight = row.type == RowType.MODULE_HEADER || row.type == RowType.CHILD_HEADER
                    ? HEADER_HEIGHT : ROW_HEIGHT;
            if (inside(mouseX, mouseY, rowX, y, rowWidth, visualHeight)) {
                if (row.type == RowType.SETTING) {
                    if (!row.setting.isLabelSetting()) {
                        handleSettingClick(row.setting, mouseX, rowX, rowWidth);
                    }
                } else if (row.type == RowType.TEAM_MEMBER) {
                    if (mouseX >= rowX + rowWidth - 24) {
                        ((Equipo) row.module).eliminar(row.teamMember.getUuid());
                    }
                } else if (row.type == RowType.TEAM_EMPTY) {
                    return;
                } else {
                    handleHeaderClick(row.module, mouseX, rowX);
                }
                return;
            }
            y += rowHeight;
            if (row.type == RowType.MODULE_HEADER) lastModule = row.module;
        }
    }

    private void handleHeaderClick(Module module, int mouseX, int rowX) {
        if (mouseX <= rowX + ARROW_ZONE_WIDTH) {
            if (this.expanded.contains(module)) this.expanded.remove(module);
            else this.expanded.add(module);
        } else {
            module.toggle();
            GlobalModuleNotifications.announce(module);
        }
    }

    private void handleSettingClick(Settings setting, int mouseX, int x, int width) {
        if (!setting.getOwner().isSettingInteractive(setting)) return;
        if (setting.isKeybindSetting()) {
            this.listeningKeybind = setting;
            this.draggedSlider = null;
        } else if (setting.isBooleanSetting()) {
            setting.setValBoolean(!setting.getValBoolean());
            if ("perfectpathc.dev.modules.impl.Refill".equals(setting.getOwner().getClass().getName())
                    && "VPE".equalsIgnoreCase(setting.getName())
                    && setting.getValBoolean()) {
                setting.getOwner().setEnabled(true);
            }
            setting.getOwner().onSettingsChanged();
        } else if (setting.isModeSetting()) {
            setting.nextMode();
            setting.getOwner().onSettingsChanged();
        } else {
            beginSliderDrag(setting, mouseX, x, width);
        }
    }

    private void beginSliderDrag(Settings setting, int mouseX, int x, int width) {
        this.draggedSlider = setting;
        this.draggedSliderWidth = Math.max(1, Math.min(SLIDER_TRACK_WIDTH, width - 90));
        this.draggedSliderX = x + width - SLIDER_RIGHT_MARGIN - this.draggedSliderWidth;
        if (setting.isRangeSetting()) {
            float range = Math.max(0.0001F, setting.getMax() - setting.getMin());
            int lowerX = this.draggedSliderX + Math.round(this.draggedSliderWidth * (setting.getRangeMinimum() - setting.getMin()) / range);
            int upperX = this.draggedSliderX + Math.round(this.draggedSliderWidth * (setting.getRangeMaximum() - setting.getMin()) / range);
            this.draggedRangeUpper = Math.abs(mouseX - upperX) < Math.abs(mouseX - lowerX);
            updateRangeSlider(setting, this.draggedRangeUpper, mouseX, this.draggedSliderX, this.draggedSliderWidth);
        } else updateSlider(setting, mouseX, this.draggedSliderX, this.draggedSliderWidth);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.draggedSlider = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.listeningKeybind != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) this.listeningKeybind = null;
            else {
                if (keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) setKeybind(this.listeningKeybind, 0);
                else if (keyCode != Keyboard.KEY_NONE) setKeybind(this.listeningKeybind, keyCode);
                this.listeningKeybind = null;
            }
        } else if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
        } else super.keyTyped(typedChar, keyCode);
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    private static List<Module> childModules(Module module) {
        if (module != PerfectPatch.instance.getModule(1)) {
            return java.util.Collections.emptyList();
        }
        return Arrays.asList((Module) PerfectPatch.instance.getModule(2));
    }

    private static void updateSlider(Settings setting, int mouseX, int x, int width) {
        float ratio = Math.max(0.0F, Math.min(1.0F,
                (float) (mouseX - x) / Math.max(1, width)));
        setting.setValfloat(setting.getMin() + ratio * (setting.getMax() - setting.getMin()));
        setting.getOwner().onSettingsChanged();
    }

    private static void updateRangeSlider(Settings setting, boolean upper,
                                          int mouseX, int x, int width) {
        float ratio = Math.max(0.0F, Math.min(1.0F,
                (float) (mouseX - x) / Math.max(1, width)));
        float value = setting.getMin() + ratio * (setting.getMax() - setting.getMin());
        if (upper) setting.setRangeMaximum(value);
        else setting.setRangeMinimum(value);
        setting.getOwner().onSettingsChanged();
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y,
                                  int width, int height) {
        return mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height;
    }

    private static String keyName(int keyCode) {
        if (Module.isMouseBinding(keyCode)) {
            return "MOUSE:" + Module.mouseButtonFromBinding(keyCode);
        }
        return keyCode <= 0 ? "NONE" : Keyboard.getKeyName(keyCode);
    }

    private static void setKeybind(Settings setting, int keyCode) {
        setting.getOwner().setKeybinding(keyCode);
        setting.getOwner().onSettingsChanged();
    }

    private static int keybindValue(Settings setting) {
        return setting.getOwner().getKeybinding();
    }

    /** Mouse bindings belong solely to the combat GUI's instant-action modules. */
    private static boolean acceptsMouseBinding(Settings setting) {
        Module owner = setting.getOwner();
        return owner instanceof ThrowPot || owner instanceof Refill;
    }
}
