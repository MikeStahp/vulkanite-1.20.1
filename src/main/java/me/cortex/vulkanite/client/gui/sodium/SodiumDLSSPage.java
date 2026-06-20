package me.cortex.vulkanite.client.gui.sodium;

import com.google.common.collect.ImmutableList;
import me.cortex.vulkanite.client.config.DLSSConfig;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Sodium GUI page for DLSS/FSR upscaling and denoising configuration.
 * Provides options for:
 * - Denoiser type selection (None, DLSS, FSR, DLSS Ray Reconstruction)
 * - Quality preset for upscaling
 * - ReSTIR toggle
 * - Debug mode
 * - World lighting parameters
 */
public class SodiumDLSSPage {
    private static final SodiumDLSSConfig STORAGE = new SodiumDLSSConfig();

    public static OptionPage create() {
        List<OptionGroup> groups = new ArrayList<>();

        // General Settings - Enable/Disable and Denoiser Type
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Enable DLSS/FSR"))
                        .setTooltip(Text.of("Enable upscaling and denoising via DLSS or FSR."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setDLSSEnabled(value),
                                (opts) -> STORAGE.getConfig().isDLSSEnabled())
                        .build())
                .add(OptionImpl.createBuilder(DLSSConfig.DenoiserType.class, STORAGE)
                        .setName(Text.of("Upscaler Type"))
                        .setTooltip(Text.of("Select the upscaling technology to use."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.DenoiserType.class, new Text[] {
                                Text.of("None"),
                                Text.of("DLSS (NVIDIA RTX)"),
                                Text.of("FSR (AMD/Intel/NVIDIA)"),
                                Text.of("DLSS RR (Ray Reconstruction)")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setDenoiserType(value),
                                (opts) -> STORAGE.getConfig().getDenoiserType())
                        .build())
                .build());

	// Upscaler Quality Settings
	groups.add(OptionGroup.createBuilder()
		.add(OptionImpl.createBuilder(DLSSConfig.QualityPreset.class, STORAGE)
			.setName(Text.of("Upscaler Quality"))
			.setTooltip(Text.of("Performance/Quality trade-off for DLSS and FSR. Performance: 50%, Balanced: 58%, Quality: 67%, Ultra Quality: 77%, Ultra Performance: 33%, DLAA: 100% (no upscaling)."))
			.setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.QualityPreset.class, new Text[] {
				Text.of("Performance"),
				Text.of("Balanced"),
				Text.of("Quality"),
				Text.of("Ultra Performance"),
				Text.of("Ultra Quality"),
				Text.of("DLAA")
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
                .build());

        // FSR Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(Integer.class, STORAGE)
                        .setName(Text.of("FSR Sharpness"))
                        .setTooltip(Text.of("Apply sharpening to the FSR upscaled image."))
                        .setControl((opt) -> new SliderControl(opt, 0, 100, 5, (val) -> Text.of(val + "%")))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setSharpness(value / 100.0f),
                                (opts) -> (int) (STORAGE.getConfig().getSharpness() * 100))
                        .build())
                .build());

        // ReSTIR Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Enable ReSTIR"))
                        .setTooltip(Text.of("Enable Spatiotemporal Reservoir Resampling for high-quality lighting."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setReSTIREnabled(value),
                                (opts) -> STORAGE.getConfig().isReSTIREnabled())
                        .build())
                .build());

        // RT Quality Tuning
        groups.add(OptionGroup.createBuilder()
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

        // Advanced Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Motion Vectors"))
                        .setTooltip(Text.of("Enable motion vector generation for temporal upscaling."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setMotionVectorsEnabled(value),
                                (opts) -> STORAGE.getConfig().isMotionVectorsEnabled())
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                        .setName(Text.of("Jitter Enabled"))
                        .setTooltip(Text.of("Enable camera jitter for temporal upscaling quality."))
                        .setControl(TickBoxControl::new)
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setJitterEnabled(value),
                                (opts) -> STORAGE.getConfig().isJitterEnabled())
                        .build())
                .build());

        // Debug Settings
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(DLSSConfig.DebugType.class, STORAGE)
                        .setName(Text.of("Debug Visualization"))
                        .setTooltip(Text.of("Show debug visualization for DLSS/FSR processing."))
                        .setControl((opt) -> new CyclingControl<>(opt, DLSSConfig.DebugType.class, new Text[] {
                                Text.of("None"),
                                Text.of("Input"),
                                Text.of("Output"),
                                Text.of("Motion Vectors"),
                                Text.of("Depth"),
                                Text.of("Normals")
                        }))
                        .setBinding(
                                (opts, value) -> STORAGE.getConfig().setDebugType(value),
                                (opts) -> STORAGE.getConfig().getDebugType())
                        .build())
                .build());

        return new OptionPage(Text.of("Vulkanite DLSS/FSR"), ImmutableList.copyOf(groups));
    }
}
