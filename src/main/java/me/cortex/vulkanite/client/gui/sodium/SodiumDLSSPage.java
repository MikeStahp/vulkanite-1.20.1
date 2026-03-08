package me.cortex.vulkanite.client.gui.sodium;

import com.google.common.collect.ImmutableList;
import me.cortex.vulkanite.client.config.DLSSConfig;
import me.cortex.vulkanite.client.rendering.DLSSRayReconstruction.DLSSQualityPreset;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class SodiumDLSSPage {
    private static final SodiumDLSSConfig STORAGE = new SodiumDLSSConfig();

    public static OptionPage create() {
        List<OptionGroup> groups = new ArrayList<>();

        // Rendering Pipeline
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DLSSConfig.RenderingPipeline.class, STORAGE)
                        .setName(Text.of("Rendering Pipeline"))
                        .setTooltip(Text.of("Select the rendering pipeline."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.RenderingPipeline.class, new Text[] {
                                Text.of("Raster (Default)"),
                                Text.of("Deferred (Experimental)"),
                                Text.of("RTX / Path Tracing")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setRenderingPipeline(value),
                                (opts) -> STORAGE.getConfig().getRenderingPipeline())
                        .build())
                .build());

        // General Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Enable DLSS/FSR"))
                        .setTooltip(Text.of("Enable upscaling and denoising via DLSS or FSR."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setEnabled(value),
                                (opts) -> STORAGE.getConfig().isEnabled())
                        .build())
                .add(OptionImpl.createBuilder(DLSSConfig.DenoiserType.class, STORAGE)
                        .setName(Text.of("Upscaler Type"))
                        .setTooltip(Text.of("Select the upscaling technology to use."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.DenoiserType.class, new Text[] {
                                Text.of("DLSS (NVIDIA RTX)"),
                                Text.of("FSR (AMD/Intel/NVIDIA)"),
                                Text.of("Basic (Fallback)")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setDenoiserType(value),
                                (opts) -> STORAGE.getConfig().getDenoiserType())
                        .build())
                .build());

        // DLSS Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DLSSQualityPreset.class, STORAGE)
                        .setName(Text.of("DLSS Quality"))
                        .setTooltip(Text.of("Performance/Quality trade-off for DLSS."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSQualityPreset.class, new Text[] {
                                Text.of("Native (No Upscaling)"),
                                Text.of("Quality"),
                                Text.of("Balanced"),
                                Text.of("Performance"),
                                Text.of("Ultra Performance")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setQualityPreset(value),
                                (opts) -> STORAGE.getConfig().getQualityPreset())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Ray Reconstruction"))
                        .setTooltip(Text.of("Use AI-powered denoising for ray tracing (DLSS 3.5)."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setRayReconstructionEnabled(value),
                                (opts) -> STORAGE.getConfig().isRayReconstructionEnabled())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Enable ReSTIR"))
                        .setTooltip(Text.of("Enable Spatiotemporal Reservoir Resampling for high-quality lighting."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setReSTIREnabled(value),
                                (opts) -> STORAGE.getConfig().isReSTIREnabled())
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Sharpening"))
                        .setTooltip(Text.of("Apply sharpening to the upscaled image."))
                        .setControl((opt) -> new SliderControl(opt, 0, 100, 5, (val) -> Text.of(val + "%")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setSharpening(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getSharpening() * 100))
                        .build())
                .build());

        // FSR Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DLSSConfig.FSRQualityPreset.class, STORAGE)
                        .setName(Text.of("FSR Quality"))
                        .setTooltip(Text.of("Performance/Quality trade-off for FSR."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.FSRQualityPreset.class, new Text[] {
                                Text.of("Quality"),
                                Text.of("Balanced"),
                                Text.of("Performance"),
                                Text.of("Ultra Performance")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setFsrQualityPreset(value),
                                (opts) -> STORAGE.getConfig().getFsrQualityPreset())
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("FSR Sharpening"))
                        .setTooltip(Text.of("Apply sharpening to the FSR upscaled image."))
                        .setControl((opt) -> new SliderControl(opt, 0, 100, 5, (val) -> Text.of(val + "%")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setFsrSharpeningStrength(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getFsrSharpeningStrength() * 100))
                        .build())
                .build());

        // RT Quality Tuning
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Sun Intensity"))
                        .setTooltip(Text.of("Multiplier for direct sunlight strength."))
                        .setControl((opt) -> new SliderControl(opt, 0, 500, 10,
                                (val) -> Text.of(String.format("%.1fx", val / 100.0f))))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setSunIntensity(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getSunIntensity() * 100))
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Global Illumination"))
                        .setTooltip(Text.of("Multiplier for indirect bounced lighting."))
                        .setControl((opt) -> new SliderControl(opt, 0, 200, 5,
                                (val) -> Text.of(String.format("%.2fx", val / 100.0f))))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setIndirectScale(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getIndirectScale() * 100))
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Ambient Base Light"))
                        .setTooltip(Text.of("Minimum lighting applied to completely shadowed areas."))
                        .setControl((opt) -> new SliderControl(opt, 0, 100, 1,
                                (val) -> Text.of(String.format("%.2f", val / 100.0f))))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setAmbientFactor(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getAmbientFactor() * 100))
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Specular Intensity"))
                        .setTooltip(Text.of("Multiplier for reflections and specular highlights."))
                        .setControl((opt) -> new SliderControl(opt, 0, 300, 10,
                                (val) -> Text.of(String.format("%.1fx", val / 100.0f))))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setSpecularIntensity(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getSpecularIntensity() * 100))
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Gamma/Exposure"))
                        .setTooltip(Text.of("Global brightness curve adjustment."))
                        .setControl((opt) -> new SliderControl(opt, 10, 300, 5,
                                (val) -> Text.of(String.format("%.2f", val / 100.0f))))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setGamma(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getGamma() * 100))
                        .build())
                .build());

        // ReSTIR Advanced Config
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Temporal History"))
                        .setTooltip(
                                Text.of("Max frames of temporal accumulation. Higher = less noise but more ghosting."))
                        .setControl((opt) -> new SliderControl(opt, 1, 20, 1, (val) -> Text.of(val + " frames")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setRestirMaxHistory(value),
                                (opts) -> STORAGE.getConfig().getRestirMaxHistory())
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Spatial Radius"))
                        .setTooltip(Text.of(
                                "Pixel radius for spatial neighbor reuse. Higher = softer shadows but potential smudging."))
                        .setControl((opt) -> new SliderControl(opt, 1, 16, 1, (val) -> Text.of(val + " px")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setRestirSpatialRadius((float) value),
                                (opts) -> (int) STORAGE.getConfig().getRestirSpatialRadius())
                        .build())
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("Spatial Samples"))
                        .setTooltip(Text.of("Number of spatial neighbors to sample. ReSTIR default is 2."))
                        .setControl((opt) -> new SliderControl(opt, 0, 8, 1, (val) -> Text.of(val + " taps")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setRestirSpatialSamples(value),
                                (opts) -> STORAGE.getConfig().getRestirSpatialSamples())
                        .build())
                .build());

        // Advanced Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Debug Mode"))
                        .setTooltip(Text.of("Enable debug visualization (Quadrants). Disables DLSS/DLSSD processing."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setDebugMode(value),
                                (opts) -> STORAGE.getConfig().isDebugMode())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Show Performance Metrics"))
                        .setTooltip(Text.of("Display performance metrics overlay."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setShowPerformanceMetrics(value),
                                (opts) -> STORAGE.getConfig().isShowPerformanceMetrics())
                        .build())
                .build());

        return new OptionPage(Text.of("Vulkanite Settings"), ImmutableList.copyOf(groups));
    }
}
