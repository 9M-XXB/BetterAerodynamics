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
import net.minecraft.network.chat.MutableComponent;

/**
 * Settings screen for the Y-level → altitude conversion, the NACA airfoil
 * designation, and the HUD.
 *
 * <p>Offers a mode cycle button (Conventional 1 block = 1 m, or Everest mapping)
 * plus two edit boxes for the custom Y levels: the sea-level Y (reads 0 m) and
 * the Mount Everest Y (reads 8,848 m / 29,031 ft). A third box sets the NACA
 * 4-digit airfoil the elytra is simulated with (any valid designation; the
 * built-in builder regenerates the flight model live). The HUD overlay can be
 * toggled here (same setting as /aerohud), persisted to config.
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
    private EditBox nacaBox;
    private StringWidget nacaLabel;
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
            .create(left, 32, COL_WIDTH, 20,
                Component.translatable("betteraerodynamics.config.mode"),
                (btn, mode) -> refreshModeDependent());
        addRenderableWidget(modeButton);

        addRenderableWidget(new StringWidget(left, 58, COL_WIDTH, 10,
            Component.translatable("betteraerodynamics.config.sea_level_y"), this.font));
        seaLevelBox = new EditBox(this.font, left, 69, COL_WIDTH, 20,
            Component.translatable("betteraerodynamics.config.sea_level_y"));
        seaLevelBox.setMaxLength(7);
        seaLevelBox.setValue(String.valueOf(AeroConfig.seaLevelY()));
        seaLevelBox.setResponder(s -> refreshPreview());
        addRenderableWidget(seaLevelBox);

        addRenderableWidget(new StringWidget(left, 93, COL_WIDTH, 10,
            Component.translatable("betteraerodynamics.config.everest_y"), this.font));
        everestBox = new EditBox(this.font, left, 104, COL_WIDTH, 20,
            Component.translatable("betteraerodynamics.config.everest_y"));
        everestBox.setMaxLength(7);
        everestBox.setValue(String.valueOf(AeroConfig.everestY()));
        everestBox.setResponder(s -> refreshPreview());
        addRenderableWidget(everestBox);

        nacaLabel = new StringWidget(left, 127, COL_WIDTH, 10, Component.empty(), this.font);
        addRenderableWidget(nacaLabel);
        nacaBox = new EditBox(this.font, left, 138, 180, 20,
            Component.translatable("betteraerodynamics.config.naca"));
        nacaBox.setMaxLength(4);
        nacaBox.setValue(AeroConfig.naca4());
        nacaBox.setResponder(s -> refreshNacaLabel());
        addRenderableWidget(nacaBox);
        addRenderableWidget(Button.builder(
                Component.translatable("betteraerodynamics.config.naca_default", AeroConfig.DEFAULT_NACA),
                b -> nacaBox.setValue(AeroConfig.DEFAULT_NACA))
            .bounds(left + 185, 138, 65, 20).build());

        addRenderableWidget(CycleButton.booleanBuilder(
                CommonComponents.OPTION_ON, CommonComponents.OPTION_OFF, AeroConfig.hudEnabled())
            .create(left, 162, COL_WIDTH, 20,
                Component.translatable("betteraerodynamics.config.hud"),
                (btn, enabled) -> AeroConfig.setHudEnabled(enabled)));

        previewWidget = new StringWidget(left, 188, COL_WIDTH, 12, Component.empty(), this.font);
        addRenderableWidget(previewWidget);

        errorWidget = new StringWidget(left, 188, COL_WIDTH, 12, Component.empty(), this.font);
        errorWidget.visible = false;
        addRenderableWidget(errorWidget);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> saveAndClose())
            .bounds(cx - 105, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(cx + 5, this.height - 28, 100, 20).build());

        refreshModeDependent();
        refreshNacaLabel();
        refreshPreview();
    }

    /** Gray out the Y-level boxes unless the Everest mapping is active. */
    private void refreshModeDependent() {
        boolean everest = modeButton.getValue() == AeroConfig.Mode.EVEREST;
        seaLevelBox.active = everest;
        everestBox.active = everest;
        refreshPreview();
    }

    /** Live airfoil parameters under the NACA box while typing. */
    private void refreshNacaLabel() {
        if (nacaLabel == null) return;
        MutableComponent base = Component.translatable("betteraerodynamics.config.naca");
        if (nacaBox.getValue().trim().matches(AeroConfig.NACA_PATTERN)) {
            AirfoilParams p = AirfoilParams.of(nacaBox.getValue().trim());
            nacaLabel.setMessage(base.append(" — ").append(Component.translatable(
                "betteraerodynamics.config.naca.params",
                String.format("%.0f", p.maxCamberPct()),
                String.format("%.0f", p.camberPosPct()),
                String.format("%.0f", p.thicknessPct()))));
        } else {
            nacaLabel.setMessage(base);
        }
    }

    private void refreshPreview() {
        if (previewWidget == null) return;
        showInfo(Component.empty());
        if (modeButton.getValue() == AeroConfig.Mode.CONVENTIONAL) {
            int worldSea = (this.minecraft != null && this.minecraft.level != null)
                ? this.minecraft.level.getSeaLevel() : 63;
            showInfo(Component.translatable(
                "betteraerodynamics.config.preview.conventional", worldSea));
        } else {
            Integer sea = parseInt(seaLevelBox.getValue());
            Integer everest = parseInt(everestBox.getValue());
            if (sea == null || everest == null || everest - sea < 1) return;
            double ftPerBlock = AeroConfig.EVEREST_ALTITUDE_FT / (everest - sea);
            double mPerBlock = ftPerBlock / AeroConfig.FT_PER_M;
            showInfo(Component.translatable(
                "betteraerodynamics.config.preview.everest",
                String.format("%.2f", mPerBlock),
                String.format("%.2f", ftPerBlock),
                sea, everest));
        }
    }

    /** Preview and error share one row — only one is visible at a time. */
    private void showInfo(Component message) {
        previewWidget.setMessage(message);
        previewWidget.visible = true;
        errorWidget.visible = false;
    }

    private void showError(Component message) {
        errorWidget.setMessage(message);
        errorWidget.visible = true;
        previewWidget.visible = false;
    }

    private void saveAndClose() {
        Integer sea = parseInt(seaLevelBox.getValue());
        Integer everest = parseInt(everestBox.getValue());
        String naca = nacaBox.getValue().trim();
        if (sea == null || everest == null || everest - sea < 1) {
            showError(Component.translatable("betteraerodynamics.config.invalid").withStyle(ChatFormatting.RED));
            return;
        }
        if (!naca.matches(AeroConfig.NACA_PATTERN)) {
            showError(Component.translatable("betteraerodynamics.config.invalid_naca").withStyle(ChatFormatting.RED));
            return;
        }
        AeroConfig.set(modeButton.getValue(), sea, everest, naca);
        onClose();
    }

    private static Integer parseInt(String text) {
        String t = text.trim();
        return t.matches(INT_PATTERN) ? Integer.valueOf(t) : null;
    }

    /** Decoded NACA 4-digit parameters (mirrors {@code AirfoilProfile}'s parsing). */
    private record AirfoilParams(double maxCamberPct, double camberPosPct, double thicknessPct) {
        static AirfoilParams of(String naca) {
            return new AirfoilParams(
                (naca.charAt(0) - '0') * 1.0,          // % max camber
                (naca.charAt(1) - '0') * 10.0,         // % camber position
                ((naca.charAt(2) - '0') * 10 + (naca.charAt(3) - '0')) * 1.0); // % thickness
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
