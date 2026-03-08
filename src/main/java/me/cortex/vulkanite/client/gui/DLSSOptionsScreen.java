package me.cortex.vulkanite.client.gui;

import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.client.rendering.DLSSRayReconstruction;
import me.cortex.vulkanite.client.rendering.DLSSLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * DLSS Options Screen
 * 
 * This screen provides a user interface for configuring DLSS/FSR settings
 * within the Sodium video options menu.
 * 
 * Features:
 * - Denoiser selection (DLSS, FSR, Basic)
 * - Enable/Disable toggle
 * - Quality preset selection
 * - Ray Reconstruction toggle
 * - Sharpening strength adjustment
 * 
 * The screen integrates with Sodium's video options menu and saves
 * settings to DLSSConfig.
 */
public class DLSSOptionsScreen extends Screen {
    
    private final Screen parent;
    private final DLSSConfig config;
    
    // Widgets
    private ClickableWidget denoiserButton;
    private ClickableWidget enabledButton;
    private ClickableWidget qualityPresetButton;
    private ClickableWidget rayReconstructionButton;
    private ClickableWidget sharpeningButton;
    private ClickableWidget fsrQualityPresetButton;
    private ClickableWidget fsrSharpeningButton;
    
    public DLSSOptionsScreen(Screen parent) {
        super(Text.literal("DLSS/FSR Settings"));
        this.parent = parent;
        this.config = DLSSConfig.load();
    }
    
    @Override
    protected void init() {
        super.init();
        
        int y = this.height / 6;
        int buttonWidth = 250;
        int leftColumn = (this.width - buttonWidth) / 2;
        
        // Title
        y += 10;
        
        // Denoiser Selection Button
        denoiserButton = ButtonWidget.builder(
            getDenoiserText(),
            button -> cycleDenoiser()
        ).dimensions(leftColumn, y, buttonWidth, 20).build();
        this.addDrawableChild(denoiserButton);
        y += 24;
        
        // Information message (shown when DLSS is selected)
        if (config.getDenoiserType() == DLSSConfig.DenoiserType.DLSS) {
            // DLSSD availability status
            boolean dlssdAvailable = DLSSLoader.getInstance() != null;
            
            if (!dlssdAvailable) {
                // Add Recheck button if DLSS is not available
                this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Recheck DLSS"),
                    button -> {
                        DLSSLoader.load();
                        // Re-init screen to update status
                        this.clearChildren();
                        this.init();
                    }
                ).dimensions(leftColumn + buttonWidth + 5, y - 24, 80, 20).build());
            }
            
            y += 5; // Small spacing
        }
        
        // Enable/Disable Toggle
        enabledButton = ButtonWidget.builder(
            getEnabledText(),
            button -> toggleEnabled()
        ).dimensions(leftColumn, y, buttonWidth, 20).build();
        this.addDrawableChild(enabledButton);
        y += 24;
        
        // DLSS Quality Preset (only shown when DLSS is selected)
        if (config.getDenoiserType() == DLSSConfig.DenoiserType.DLSS) {
            qualityPresetButton = ButtonWidget.builder(
                getQualityPresetText(),
                button -> cycleQualityPreset()
            ).dimensions(leftColumn, y, buttonWidth, 20).build();
            this.addDrawableChild(qualityPresetButton);
            y += 24;
            
            // Ray Reconstruction Toggle
            rayReconstructionButton = ButtonWidget.builder(
                getRayReconstructionText(),
                button -> toggleRayReconstruction()
            ).dimensions(leftColumn, y, buttonWidth, 20).build();
            this.addDrawableChild(rayReconstructionButton);
            y += 24;
            
            // Sharpening Button (for DLSS)
            sharpeningButton = ButtonWidget.builder(
                getSharpeningText(),
                button -> cycleSharpening()
            ).dimensions(leftColumn, y, buttonWidth, 20).build();
            this.addDrawableChild(sharpeningButton);
            y += 24;
        }
        
        // FSR Quality Preset (only shown when FSR is selected)
        if (config.getDenoiserType() == DLSSConfig.DenoiserType.FSR) {
            fsrQualityPresetButton = ButtonWidget.builder(
                getFSRQualityPresetText(),
                button -> cycleFSRQualityPreset()
            ).dimensions(leftColumn, y, buttonWidth, 20).build();
            this.addDrawableChild(fsrQualityPresetButton);
            y += 24;
            
            // FSR Sharpening Button
            fsrSharpeningButton = ButtonWidget.builder(
                getFSRSharpeningText(),
                button -> cycleFSRSharpening()
            ).dimensions(leftColumn, y, buttonWidth, 20).build();
            this.addDrawableChild(fsrSharpeningButton);
            y += 24;
        }
        
        // Done Button
        this.addDrawableChild(
            ButtonWidget.builder(
                Text.literal("Done"),
                button -> close()
            ).dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build()
        );
    }
    
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
    
    private Text getDenoiserText() {
        DLSSConfig.DenoiserType type = config.getDenoiserType();
        String status = getAvailabilityStatus(type);
        return Text.literal("Denoiser: " + type + " " + status);
    }
    
    private String getAvailabilityStatus(DLSSConfig.DenoiserType type) {
        if (type == DLSSConfig.DenoiserType.DLSS) {
            return DLSSLoader.isAvailable() ? "(Ready)" : "(Missing DLLs)";
        }
        return "";
    }
    
    private void cycleDenoiser() {
        DLSSConfig.DenoiserType[] types = DLSSConfig.DenoiserType.values();
        DLSSConfig.DenoiserType current = config.getDenoiserType();
        int nextIndex = (current.ordinal() + 1) % types.length;
        config.setDenoiserType(types[nextIndex]);
        
        denoiserButton.setMessage(getDenoiserText());
        saveConfig();
        recreateWidgets();
    }
    
    private Text getEnabledText() {
        return Text.literal("Upscaler: " + (config.isEnabled() ? "ON" : "OFF"));
    }
    
    private void toggleEnabled() {
        config.setEnabled(!config.isEnabled());
        enabledButton.setMessage(getEnabledText());
        saveConfig();
    }
    
    private Text getQualityPresetText() {
        return Text.literal("DLSS Quality: " + config.getQualityPreset());
    }
    
    private void cycleQualityPreset() {
        DLSSRayReconstruction.DLSSQualityPreset[] presets = DLSSRayReconstruction.DLSSQualityPreset.values();
        DLSSRayReconstruction.DLSSQualityPreset current = config.getQualityPreset();
        int nextIndex = (current.ordinal() + 1) % presets.length;
        config.setQualityPreset(presets[nextIndex]);
        
        qualityPresetButton.setMessage(getQualityPresetText());
        saveConfig();
    }
    
    private Text getRayReconstructionText() {
        return Text.literal("Ray Reconstruction: " + (config.isRayReconstructionEnabled() ? "ON" : "OFF"));
    }
    
    private void toggleRayReconstruction() {
        config.setRayReconstructionEnabled(!config.isRayReconstructionEnabled());
        rayReconstructionButton.setMessage(getRayReconstructionText());
        saveConfig();
    }
    
    private Text getSharpeningText() {
        return Text.literal(String.format("DLSS Sharpening: %.1f", config.getSharpening()));
    }
    
    private void cycleSharpening() {
        float current = config.getSharpening();
        float next = current + 0.1f;
        if (next > 1.0f) {
            next = 0.0f;
        }
        config.setSharpening(next);
        sharpeningButton.setMessage(getSharpeningText());
        saveConfig();
    }
    
    private Text getFSRQualityPresetText() {
        return Text.literal("FSR Quality: " + config.getFsrQualityPreset());
    }
    
    private void cycleFSRQualityPreset() {
        DLSSConfig.FSRQualityPreset[] presets = DLSSConfig.FSRQualityPreset.values();
        DLSSConfig.FSRQualityPreset current = config.getFsrQualityPreset();
        int nextIndex = (current.ordinal() + 1) % presets.length;
        config.setFsrQualityPreset(presets[nextIndex]);
        
        fsrQualityPresetButton.setMessage(getFSRQualityPresetText());
        saveConfig();
    }
    
    private Text getFSRSharpeningText() {
        return Text.literal(String.format("FSR Sharpening: %.1f", config.getFsrSharpeningStrength()));
    }
    
    private void cycleFSRSharpening() {
        float current = config.getFsrSharpeningStrength();
        float next = current + 0.1f;
        if (next > 1.0f) {
            next = 0.0f;
        }
        config.setFsrSharpeningStrength(next);
        fsrSharpeningButton.setMessage(getFSRSharpeningText());
        saveConfig();
    }
    
    private void saveConfig() {
        config.save();
    }
    
    private void recreateWidgets() {
        this.clearChildren();
        this.init();
    }
    
    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(parent);
    }
}
