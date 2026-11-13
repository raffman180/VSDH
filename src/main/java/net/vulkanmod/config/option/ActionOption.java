package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.ActionOptionWidget;
import net.vulkanmod.config.gui.widget.OptionWidget;

/** A row with a single button that runs {@code action} at once; it holds no value to apply or undo. */
public class ActionOption extends Option<Boolean> {
    private final Component label;
    private final Runnable action;

    public ActionOption(Component name, Component label, Runnable action) {
        super(name, ignored -> {}, () -> Boolean.FALSE, ignored -> label);
        this.label = label;
        this.action = action;
    }

    public Component label() {
        return this.label;
    }

    public void run() {
        this.action.run();
    }

    @Override
    protected OptionWidget<?> createWidget() {
        var widget = new ActionOptionWidget(this, this.name);
        this.widget = widget;
        return widget;
    }
}
