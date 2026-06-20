# DLSS Config Cleanup Design

## Executive Summary

This document analyzes the current state of DLSS configuration in the Vulkanite project and proposes a clean architecture for `DLSSConfig`. The analysis reveals that **DLSSConfig class does not exist in the codebase** despite being referenced by multiple components, creating a critical architectural gap.

---

## 1. Current State Analysis

### 1.1 Missing Core Configuration Class

**Critical Finding**: The class `me.cortex.vulkanite.client.config.DLSSConfig` is **referenced but does not exist**.

| File | Reference | Status |
|------|-----------|--------|
| [`SodiumDLSSConfig.java`](src/main/java/me/cortex/vulkanite/client/gui/sodium/SodiumDLSSConfig.java:3) | `import me.cortex.vulkanite.client.config.DLSSConfig` | Missing |
| [`ResolutionScaleManager.java`](src/main/java/me/cortex/vulkanite/client/rendering/ResolutionScaleManager.java:62) | `DLSSConfig.load()` | Missing |
| [`VulkanPipeline.java`](src/main/java/me/cortex/vulkanite/client/rendering/VulkanPipeline.java:321) | `DLSSConfig.load().isRayReconstructionEnabled()` | Missing |
| [`MixinProgramSet.java`](src/main/java/me/cortex/vulkanite/mixin/iris/MixinProgramSet.java:53) | `DLSSConfig.load()` | Missing |

### 1.2 Existing Configuration Classes

| Class | Location | Purpose |
|-------|----------|---------|
| [`VulkaniteConfig`](src/main/java/me/cortex/vulkanite/client/config/VulkaniteConfig.java) | `client/config/` | General mod settings (ReSTIR, deferred rendering) |
| [`SodiumDLSSConfig`](src/main/java/me/cortex/vulkanite/client/gui/sodium/SodiumDLSSConfig.java) | `gui/sodium/` | Sodium GUI storage adapter (references missing DLSSConfig) |

### 1.3 Configuration Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Current Broken Flow                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  SodiumDLSSConfig ─────► DLSSConfig (MISSING) ────────► ???     │
│        │                    │                                   │
│        │                    ├── isEnabled()                     │
│        │                    ├── getDenoiser()                   │
│        │                    ├── getQualityPreset()              │
│        │                    ├── isRayReconstructionEnabled()    │
│        │                    ├── isReSTIREnabled()               │
│        │                    ├── isDebugEnabled()                │
│        │                    └── FSRQualityPreset enum           │
│        │                                                        │
│        └────────── Uses singleton pattern via getInstance()     │
│                                                                 │
│  ResolutionScaleManager ──► DLSSConfig.load() ──► MISSING       │
│  VulkanPipeline ──────────► DLSSConfig.load() ──► MISSING       │
│  MixinProgramSet ─────────► DLSSConfig.load() ──► MISSING       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.4 Expected DLSSConfig API Surface

Based on usage analysis, DLSSConfig must provide:

| Method | Return Type | Used By |
|--------|-------------|---------|
| `getInstance()` | `DLSSConfig` | SodiumDLSSConfig |
| `load()` | `DLSSConfig` | ResolutionScaleManager, VulkanPipeline, MixinProgramSet |
| `save()` | `void` | SodiumDLSSConfig |
| `isEnabled()` | `boolean` | ResolutionScaleManager, VulkanPipeline |
| `getDenoiser()` | `String` | ResolutionScaleManager |
| `getQualityPreset()` | `QualityPreset` | ResolutionScaleManager |
| `getQualityPreset().getScale()` | `float` | ResolutionScaleManager |
| `isRayReconstructionEnabled()` | `boolean` | VulkanPipeline, MixinProgramSet |
| `isReSTIREnabled()` | `boolean` | VulkanPipeline, MixinProgramSet, RtxResourceManager |
| `isDebugEnabled()` | `boolean` | VulkanPipeline |
| `getDebugType()` | `DebugType` | VulkanPipeline |
| `getDebugCellIndex()` | `int` | VulkanPipeline |
| `getIndirectScale()` | `float` | MixinProgramSet |
| `getAmbientFactor()` | `float` | MixinProgramSet |
| `getMinLighting()` | `float` | MixinProgramSet |
| `getSpecularIntensity()` | `float` | MixinProgramSet |
| `getGamma()` | `float` | MixinProgramSet |

### 1.5 Nested Types Expected

```java
// FSRQualityPreset enum (referenced in FSRUpscaler.java)
public enum FSRQualityPreset {
    QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE;
    
    public float getScale() {
        switch (this) {
            case QUALITY: return 0.667f;
            case BALANCED: return 0.583f;
            case PERFORMANCE: return 0.5f;
            case ULTRA_PERFORMANCE: return 0.333f;
            default: return 1.0f;
        }
    }
}
```

---

## 2. Identified Problems

### 2.1 Critical: Missing Configuration Class

**Problem**: `DLSSConfig` class does not exist, causing compilation failures or runtime errors.

**Impact**: 
- Build failures when compiling files that import DLSSConfig
- Runtime ClassNotFoundException when accessing DLSS settings
- GUI cannot save/load DLSS preferences

### 2.2 Configuration Fragmentation

**Problem**: Configuration logic is scattered across multiple files:

| Component | Configuration Logic |
|-----------|---------------------|
| [`ResolutionScaleManager`](src/main/java/me/cortex/vulkanite/client/rendering/ResolutionScaleManager.java:25-29) | Defines scale factors (NATIVE=1.0, QUALITY=0.667, etc.) |
| [`FSRUpscaler`](src/main/java/me/cortex/vulkanite/client/rendering/FSRUpscaler.java:39) | References `DLSSConfig.FSRQualityPreset` |
| [`VulkaniteConfig`](src/main/java/me/cortex/vulkanite/client/config/VulkaniteConfig.java) | Stores ReSTIR settings separately |

### 2.3 Inconsistent Access Patterns

**Problem**: Mixed singleton and static load() patterns:

```java
// Pattern 1: Singleton (SodiumDLSSConfig)
DLSSConfig.getInstance().save();

// Pattern 2: Static load (ResolutionScaleManager, VulkanPipeline)
DLSSConfig config = DLSSConfig.load();
```

### 2.4 Duplicate Enum Definitions

**Problem**: Quality presets defined in multiple places:

- `ResolutionScaleManager` has scale factor constants
- `FSRUpscaler` references `DLSSConfig.FSRQualityPreset`
- Native bridge has `NVSDK_NGX_PerfQuality_Value`

### 2.5 Missing Configuration Persistence

**Problem**: No file persistence mechanism for DLSS settings:
- No properties file for DLSS-specific settings
- Settings may reset on game restart
- No integration with Minecraft's config system

---

## 3. Proposed Clean Architecture

### 3.1 Single Source of Truth

```
┌─────────────────────────────────────────────────────────────────┐
│                    Proposed Clean Architecture                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    DLSSConfig                            │   │
│  │  (Singleton + File Persistence)                          │   │
│  │                                                          │   │
│  │  - enabled: boolean                                      │   │
│  │  - denoiser: DenoiserType (DLSS/FSR/NONE)               │   │
│  │  - qualityPreset: QualityPreset                          │   │
│  │  - rayReconstruction: boolean                            │   │
│  │  - reSTIR: boolean                                       │   │
│  │  - debug: DebugConfig                                    │   │
│  │  - lighting: LightingConfig                              │   │
│  │                                                          │   │
│  │  + getInstance(): DLSSConfig                             │   │
│  │  + load(): DLSSConfig                                    │   │
│  │  + save(): void                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              vulkanite-dlss.properties                   │   │
│  │  (Fabric config directory)                               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Consumers:                                                    │
│  - ResolutionScaleManager ──► getQualityPreset().getScale()   │
│  - VulkanPipeline ──────────► isRayReconstructionEnabled()    │
│  - MixinProgramSet ─────────► getLightingConfig()             │
│  - SodiumDLSSConfig ────────► getInstance()                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Class Design

```java
package me.cortex.vulkanite.client.config;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.util.Properties;

public class DLSSConfig {
    private static final String CONFIG_FILE = "vulkanite-dlss.properties";
    private static DLSSConfig INSTANCE;
    
    // Core Settings
    private boolean enabled = false;
    private DenoiserType denoiser = DenoiserType.NONE;
    private QualityPreset qualityPreset = QualityPreset.QUALITY;
    
    // DLSSD Settings
    private boolean rayReconstruction = false;
    private boolean reSTIR = false;
    
    // Debug Settings
    private DebugType debugType = DebugType.NONE;
    private int debugCellIndex = -1;
    
    // Lighting Settings
    private float indirectScale = 1.0f;
    private float ambientFactor = 0.1f;
    private float minLighting = 0.01f;
    private float specularIntensity = 1.0f;
    private float gamma = 2.2f;
    
    // Singleton Access
    public static synchronized DLSSConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new DLSSConfig();
            INSTANCE.load();
        }
        return INSTANCE;
    }
    
    // Static Load (for convenience)
    public static DLSSConfig load() {
        return getInstance();
    }
    
    // Persistence
    public void save() {
        File file = FabricLoader.getInstance().getConfigDir()
            .resolve(CONFIG_FILE).toFile();
        // ... Properties save logic
    }
    
    private void load() {
        File file = FabricLoader.getInstance().getConfigDir()
            .resolve(CONFIG_FILE).toFile();
        // ... Properties load logic
    }
    
    // Getters
    public boolean isEnabled() { return enabled; }
    public DenoiserType getDenoiser() { return denoiser; }
    public QualityPreset getQualityPreset() { return qualityPreset; }
    public boolean isRayReconstructionEnabled() { return rayReconstruction; }
    public boolean isReSTIREnabled() { return reSTIR; }
    public boolean isDebugEnabled() { return debugType != DebugType.NONE; }
    public DebugType getDebugType() { return debugType; }
    public int getDebugCellIndex() { return debugCellIndex; }
    
    // Lighting Getters
    public float getIndirectScale() { return indirectScale; }
    public float getAmbientFactor() { return ambientFactor; }
    public float getMinLighting() { return minLighting; }
    public float getSpecularIntensity() { return specularIntensity; }
    public float getGamma() { return gamma; }
    
    // Setters (with auto-save option)
    public void setEnabled(boolean enabled) { 
        this.enabled = enabled; 
    }
    // ... other setters
    
    // Nested Enums
    public enum DenoiserType {
        NONE, DLSS, FSR
    }
    
    public enum QualityPreset {
        NATIVE(1.0f),
        QUALITY(0.667f),
        BALANCED(0.583f),
        PERFORMANCE(0.5f),
        ULTRA_PERFORMANCE(0.333f);
        
        private final float scale;
        
        QualityPreset(float scale) { this.scale = scale; }
        public float getScale() { return scale; }
    }
    
    public enum DebugType {
        NONE, DLSS, BUFFERS
    }
}
```

### 3.3 File Format

```properties
# vulkanite-dlss.properties
# DLSS Configuration for Vulkanite RT

# Core Settings
enabled=false
denoiser=NONE
qualityPreset=QUALITY

# DLSSD Settings
rayReconstruction=false
reSTIR=false

# Debug Settings
debugType=NONE
debugCellIndex=-1

# Lighting Settings
indirectScale=1.0
ambientFactor=0.1
minLighting=0.01
specularIntensity=1.0
gamma=2.2
```

---

## 4. Necessary Changes

### 4.1 Create Missing Files

| Priority | File | Action |
|----------|------|--------|
| **CRITICAL** | `src/main/java/me/cortex/vulkanite/client/config/DLSSConfig.java` | Create new file |

### 4.2 Code Changes Required

#### 4.2.1 Create DLSSConfig.java

- Implement singleton pattern with `getInstance()`
- Implement static `load()` for convenience
- Add all required getters/setters
- Add nested enums: `DenoiserType`, `QualityPreset`, `DebugType`
- Add file persistence using Properties
- Store in Fabric config directory

#### 4.2.2 Update ResolutionScaleManager

**Current** ([`ResolutionScaleManager.java:62-68`](src/main/java/me/cortex/vulkanite/client/rendering/ResolutionScaleManager.java:62)):
```java
DLSSConfig config = DLSSConfig.load();
boolean dlssEnabled = config.isEnabled() && "DLSS".equals(config.getDenoiser());
float scale = 1.0f;
if (dlssEnabled) {
    scale = config.getQualityPreset().getScale();
}
```

**Proposed**:
```java
DLSSConfig config = DLSSConfig.getInstance();
boolean dlssEnabled = config.isEnabled() && config.getDenoiser() == DLSSConfig.DenoiserType.DLSS;
float scale = config.getQualityPreset().getScale();
```

#### 4.2.3 Update FSRUpscaler

**Current** ([`FSRUpscaler.java:39`](src/main/java/me/cortex/vulkanite/client/rendering/FSRUpscaler.java:39)):
```java
private me.cortex.vulkanite.client.config.DLSSConfig.FSRQualityPreset qualityPreset;
```

**Proposed**: Use `DLSSConfig.QualityPreset` instead of separate FSR enum, or keep FSR-specific enum if FSR has different scale factors.

#### 4.2.4 Update MixinProgramSet

**Current** ([`MixinProgramSet.java:53-90`](src/main/java/me/cortex/vulkanite/mixin/iris/MixinProgramSet.java:53)):
```java
DLSSConfig dlssConfig = DLSSConfig.load();
boolean enableDLSSRR = dlssConfig.isRayReconstructionEnabled();
// ...
definesBuilder.append("#define ENABLE_DLSS_RR ").append(enableDLSSRR ? 1 : 0).append("\n");
```

**Proposed**: No changes needed, just ensure DLSSConfig exists.

#### 4.2.5 Consolidate VulkaniteConfig

**Consideration**: Merge ReSTIR settings from [`VulkaniteConfig`](src/main/java/me/cortex/vulkanite/client/config/VulkaniteConfig.java) into DLSSConfig, or keep separate for modularity.

**Recommendation**: Keep separate but ensure consistent access pattern:
- `VulkaniteConfig`: General mod settings (deferred rendering, etc.)
- `DLSSConfig`: DLSS/FSR-specific settings

### 4.3 Native Bridge Alignment

Ensure native bridge ([`dlss_wrapper.cpp`](dlss_bridge/dlss_wrapper.cpp)) quality values align with Java enum:

| Java QualityPreset | NGX Value |
|-------------------|-----------|
| NATIVE | NVSDK_NGX_PerfQuality_Value_DLAA |
| QUALITY | NVSDK_NGX_PerfQuality_Value_MaxQuality |
| BALANCED | NVSDK_NGX_PerfQuality_Value_Balanced |
| PERFORMANCE | NVSDK_NGX_PerfQuality_Value_MaxPerf |
| ULTRA_PERFORMANCE | NVSDK_NGX_PerfQuality_Value_UltraPerformance |

---

## 5. Implementation Checklist

### Phase 1: Critical Fix
- [ ] Create `DLSSConfig.java` with all required methods
- [ ] Add nested enums: `DenoiserType`, `QualityPreset`, `DebugType`
- [ ] Implement file persistence
- [ ] Test singleton/load patterns work correctly

### Phase 2: Integration
- [ ] Verify all consumers compile and work
- [ ] Update `ResolutionScaleManager` to use enum comparison
- [ ] Test GUI save/load functionality
- [ ] Verify native bridge quality mapping

### Phase 3: Cleanup
- [x] Remove duplicate scale factor constants from `ResolutionScaleManager`
- [x] Consolidate FSR quality preset handling
- [x] Add validation for configuration values
- [x] Add logging for configuration changes

---

## 6. Testing Recommendations

1. **Unit Tests**:
   - Test singleton pattern returns same instance
   - Test load() returns valid config
   - Test save() persists to file
   - Test enum getScale() returns correct values

2. **Integration Tests**:
   - Test ResolutionScaleManager uses correct scale
   - Test VulkanPipeline reads correct settings
   - Test MixinProgramSet injects correct defines
   - Test GUI saves and loads correctly

3. **Runtime Tests**:
   - Verify DLSS enables/disables correctly
   - Verify quality preset changes resolution
   - Verify settings persist across restarts

---

## 7. Summary

The DLSS configuration system has a critical gap: the `DLSSConfig` class is missing entirely. This must be created to:

1. Enable DLSS/FSR configuration persistence
2. Provide single source of truth for quality presets
3. Support GUI configuration via Sodium integration
4. Enable proper upscaling/denoising feature toggles

The proposed design follows the existing `VulkaniteConfig` pattern and provides all methods expected by current consumers.
