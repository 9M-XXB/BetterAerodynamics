package com.betteraerodynamics.client;

import com.betteraerodynamics.config.AeroConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Settings screen for the Y-level → altitude conversion and the HUD.
 *
 * <p>Offers a mode cycle button (Conventional 1 block = 1 m, or Everest mapping)
 * plus two edit boxes for the custom Y levels: the sea-level Y (reads 0 m) and
 * the Mount Everest Y (reads 8,848 m / 29,031 ft). Both units are metres so the
 * two reference heights read consistently. The HUD overlay can be toggled here
 * (same setting as /aerohud), persisted to config.
 *
 * <p>Composed purely of self-rendering widgets — no custom draw code — so it
 * survives vanilla render-pipeline changes.
 */
public class AeroConfigScreen extends Screen {
    private static final String INT_PATTERN = "-?\\d+";
    private static final int COL_WIDTH = 250;

    private final Screen parent;

    private CycleButton<AeroConfig.Mode> modeButton;
    private EditBox seaLevelBox;
    private EditBox everestBox;
    private StringWidget previewWidget;
    private StringWidget errorWidget;

    public AeroConfigScreen(Screen parent) {
        super(Component.translatable("betteraerodynamics.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - COL_WIDTH / 2;

        modeButton = CycleButton.builder(
                mode -> Component.translatable("betteraerodynamics.config.mode." + mode.name().toLowerCase()),
                AeroConfig.mode())
            .withValues(AeroConfig.Mode.values())
            .create(left, 40, COL_WIDTH, 20,
                Component.translatable("betteraerodynamics.config.mode"),
                (btn, mode) -> refreshModeDependent());
        addRenderableWidget(modeButton);

        addRenderableWidget(new StringWidget(left, 68, COL_WIDTH, 10,
            Component.translatable("betteraerodynamics.config.sea_level_y"), this.font));
        seaLevelBox = new EditBox(this.font, left, 79, COL_WIDTH, 20,
            Component.translatable("betteraerodynamics.config.sea_level_y"));
        seaLevelBox.setMaxLength(7);
        seaLevelBox.setValue(String.valueOf(AeroConfig.seaLevelY()));
        seaLevelBox.setResponder(s -> refreshPreview());
        addRenderableWidget(seaLevelBox);

        addRenderableWidget(new StringWidget(left, 105, COL_WIDTH, 10,
            Component.translatable("betteraerodynamics.config.everest_y"), this.font));
        everestBox = new EditBox(this.font, left, 116, COL_WIDTH, 20,
            Component.translatable("betteraerodynamics.config.everest_y"));
        everestBox.setMaxLength(7);
        everestBox.setValue(String.valueOf(AeroConfig.everestY()));
        everestBox.setResponder(s -> refreshPreview());
        addRenderableWidget(everestBox);

        addRenderableWidget(CycleButton.booleanBuilder(
                CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, AeroConfig.hudEnabled())
            .create(left, 142, COL_WIDTH, 20,
                Component.translatable("betteraerodynamics.config.hud"),
                (btn, enabled) -> AeroConfig.setHudEnabled(enabled)));

        previewWidget = new StringWidget(left, 170, COL_WIDTH, 12, Component.empty(), this.font);
        addRenderableWidget(previewWidget);

        errorWidget = new StringWidget(left, 184, COL_WIDTH, 12,
            Component.translatable("betteraerodynamics.config.invalid").withStyle(ChatFormatting.RED), this.font);
        errorWidget.visible = false;
        addRenderableWidget(errorWidget);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> saveAndClose())
            .bounds(cx - 105, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(cx + 5, this.height - 28, 100, 20).build());

        refreshModeDependent();
        refreshPreview();
    }

    /** Gray out the Y-level boxes unless the Everest mapping is active. */
    private void refreshModeDependent() {
        boolean everest = modeButton.getValue() == AeroConfig.Mode.EVEREST;
        seaLevelBox.active = everest;
        everestBox.active = everest;
        refreshPreview();
    }

    private void refreshPreview() {
        if (previewWidget == null) return;
        if (modeButton.getValue() == AeroConfig.Mode.CONVENTIONAL) {
            int worldSea = (this.minecraft != null && this.minecraft.level != null)
                ? this.minecraft.level.getSeaLevel() : 63;
            previewWidget.setMessage(Component.translatable(
                "betteraerodynamics.config.preview.conventional", worldSea));
        } else {
            Integer sea = parseInt(seaLevelBox.getValue());
            Integer everest = parseInt(everestBox.getValue());
            if (sea == null || everest == null || everest - sea < 1) {
                previewWidget.setMessage(Component.empty());
            } else {
                double ftPerBlock = AeroConfig.EVEREST_ALTITUDE_FT / (everest - sea);
                double mPerBlock = ftPerBlock / AeroConfig.FT_PER_M;
                previewWidget.setMessage(Component.translatable(
                    "betteraerodynamics.config.preview.everest",
                    String.format("%.2f", mPerBlock),
                    String.format("%.2f", ftPerBlock),
                    sea, everest));
            }
        }
    }

    private void saveAndClose() {
        Integer sea = parseInt(seaLevelBox.getValue());
        Integer everest = parseInt(everestBox.getValue());
        if (sea == null || everest == null || everest - sea < 1) {
            errorWidget.visible = true;
            return;
        }
        AeroConfig.set(modeButton.getValue(), sea, everest);
        onClose();
    }

    private static Integer parseInt(String text) {
        String t = text.trim();
        return t.matches(INT_PATTERN) ? Integer.valueOf(t) : null;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
