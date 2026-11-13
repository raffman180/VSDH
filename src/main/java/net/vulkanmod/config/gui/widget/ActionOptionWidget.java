package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.option.ActionOption;
import net.vulkanmod.vulkan.util.ColorUtil;

public class ActionOptionWidget extends OptionWidget<ActionOption> {
    private boolean focused;

    public ActionOptionWidget(ActionOption option, Component name) {
        super(option, name);
        this.displayedValue = option.label();
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        int x0 = this.controlX + 4, y0 = this.y + 3;
        int w = this.controlWidth - 8, h = this.height - 6;
        boolean hovered = this.active && mouseX >= x0 && mouseX <= x0 + w && mouseY >= y0 && mouseY <= y0 + h;
        GuiRenderer.fillBox(x0, y0, w, h, ColorUtil.ARGB.pack(1.0f, 1.0f, 1.0f, hovered ? 0.25f : 0.12f));
        GuiRenderer.renderBoxBorder(x0, y0, w, h, 1, ColorUtil.ARGB.pack(0.6f, 0.6f, 0.6f, 1.0f));
        int color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        GuiRenderer.drawScrollingString(Minecraft.getInstance().font, this.option.label(),
                this.controlX + this.controlWidth / 2, this.y + (this.height - 9) / 2, w - 8, color);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active) this.option.run();
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
    }

    @Override
    protected void updateDisplayedValue() {
        this.displayedValue = this.option.label();
    }

    @Override
    public void setFocused(boolean bl) {
        this.focused = bl;
    }

    @Override
    public boolean isFocused() {
        return this.focused;
    }
}
