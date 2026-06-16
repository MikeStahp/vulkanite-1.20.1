# DLSSD (Ray Reconstruction) Parameter Reference

Este documento describe todos los parámetros requeridos y opcionales para DLSSD (Deep Learning Super Sampling - Ray Reconstruction) basado en el SDK de NVIDIA NGX.

## Ubicación de Archivos de Referencia

- **Headers del SDK**: `dlss_bridge/Include/`
- **Definiciones**: `nvsdk_ngx_defs_dlssd.h`
- **Parámetros de creación**: `nvsdk_ngx_params_dlssd.h`
- **Parámetros de evaluación**: `nvsdk_ngx_helpers_dlssd_vk.h`

---

## 1. Parámetros de Creación (`NVSDK_NGX_DLSSD_Create_Params`)

Estos parámetros se usan al crear el feature DLSSD con `NGX_VULKAN_CREATE_DLSSD_EXT1`.

### 1.1 Modo de Denoise (`InDenoiseMode`)

| Valor | Nombre | Descripción |
|-------|--------|-------------|
| 0 | `NVSDK_NGX_DLSS_Denoise_Mode_Off` | Sin denoising |
| 1 | `NVSDK_NGX_DLSS_Denoise_Mode_DLUnified` | DL-based unified upscaler (RECOMENDADO) |

**Para Ray Reconstruction, usar siempre**: `NVSDK_NGX_DLSS_Denoise_Mode_DLUnified = 1`

### 1.2 Modo de Roughness (`InRoughnessMode`)

| Valor | Nombre | Descripción |
|-------|--------|-------------|
| 0 | `NVSDK_NGX_DLSS_Roughness_Mode_Unpacked` | Roughness en textura separada |
| 1 | `NVSDK_NGX_DLSS_Roughness_Mode_Packed` | Roughness empaquetado en normals.w |

**Recomendación**: Usar `Packed = 1` si los normales tienen roughness en el canal W.

### 1.3 Tipo de Depth (`InUseHWDepth`)

| Valor | Nombre | Descripción |
|-------|--------|-------------|
| 0 | `NVSDK_NGX_DLSS_Depth_Type_Linear` | Depth lineal (NO en rango 0-1) |
| 1 | `NVSDK_NGX_DLSS_Depth_Type_HW` | Hardware depth (rango 0-1) |

**Para Ray Tracing**: Usar `Linear = 0` porque el ray tracing produce depth lineal real.

### 1.4 Dimensiones

| Parámetro | Tipo | Descripción |
|-----------|------|-------------|
| `InWidth` | `unsigned int` | Ancho de renderizado (entrada) |
| `InHeight` | `unsigned int` | Alto de renderizado (entrada) |
| `InTargetWidth` | `unsigned int` | Ancho de salida (display) |
| `InTargetHeight` | `unsigned int` | Alto de salida (display) |

**Restricciones**:
- `InWidth` debe ser menor que `InTargetWidth` (DLSSD requiere upscaling real)
- Dimensiones deben estar alineadas a múltiplos de 8
- Mínimo: 8x8 píxeles

### 1.5 Quality Preset (`InPerfQualityValue`)

| Valor | Nombre en SDK | Nombre Común | Factor de Escala |
|-------|---------------|--------------|------------------|
| 0 | `NVSDK_NGX_PerfQuality_Value_MaxPerf` | Performance | 0.5 (50%) |
| 1 | `NVSDK_NGX_PerfQuality_Value_Balanced` | Balanced | 0.583 (58.3%) |
| 2 | `NVSDK_NGX_PerfQuality_Value_MaxQuality` | Quality | 0.667 (66.67%) |
| 3 | `NVSDK_NGX_PerfQuality_Value_UltraPerformance` | Ultra Performance | 0.333 (33.3%) |
| 4 | `NVSDK_NGX_PerfQuality_Value_UltraQuality` | Ultra Quality | ~0.77 (77%) |
| 5 | `NVSDK_NGX_PerfQuality_Value_DLAA` | DLAA | 1.0 (no upscaling) |

**NOTA**: DLSSD está diseñado para upscaling. El modo DLAA (valor 5) no realiza upscaling y puede no ser adecuado para todos los casos de uso de Ray Reconstruction. Los modos más comunes son MaxPerf (0), Balanced (1), y MaxQuality (2).

---

## 2. Parámetros de Evaluación (`NVSDK_NGX_VK_DLSSD_Eval_Params`)

Estos parámetros se pasan cada frame a `NGX_VULKAN_EVALUATE_DLSSD_EXT`.

### 2.1 Inputs Requeridos (Obligatorios)

#### Color Input (`pInColor`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Imagen de color con ruido del ray tracing
- **Resolución**: Render resolution (InWidth x InHeight)
- **Formatos válidos**:
  - `VK_FORMAT_R16G16B16A16_SFLOAT` (RECOMENDADO)
  - `VK_FORMAT_R32G32B32A32_SFLOAT`
  - `VK_FORMAT_R8G8B8A8_UNORM`
  - `VK_FORMAT_B8G8R8A8_UNORM`
- **Espacio de color**: Linear (NO sRGB)

#### Depth Input (`pInDepth`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Buffer de profundidad
- **Resolución**: Render resolution
- **Formatos válidos**:
  - `VK_FORMAT_R32_SFLOAT` (RECOMENDADO para depth lineal)
  - `VK_FORMAT_D32_SFLOAT`
  - `VK_FORMAT_R16_SFLOAT`
- **Tipo de depth**: Debe coincidir con `InUseHWDepth`

#### Motion Vectors (`pInMotionVectors`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Vectores de movimiento screen-space
- **Resolución**: Render resolution
- **Formatos válidos**:
  - `VK_FORMAT_R16G16_SFLOAT` (RECOMENDADO)
  - `VK_FORMAT_R32G32_SFLOAT`
- **Unidades**: Píxeles (screen-space)
- **Escala**: Por defecto 1.0, configurable con `InMVScaleX/Y`

#### Output (`pInOutput`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Imagen de salida denoised
- **Resolución**: Output/Display resolution (InTargetWidth x InTargetHeight)
- **Formatos válidos**: Mismos que Color Input

### 2.2 G-Buffer Inputs (Recomendados)

#### Diffuse Albedo (`pInDiffuseAlbedo`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Albedo difuso del G-buffer
- **Resolución**: Render resolution
- **Formatos válidos**:
  - `VK_FORMAT_R8G8B8A8_UNORM` (RECOMENDADO)
  - `VK_FORMAT_R16G16B16A16_SFLOAT`
- **Contenido**: RGB = Albedo difuso

#### Specular Albedo (`pInSpecularAlbedo`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Albedo especular / F0
- **Resolución**: Render resolution
- **Formatos válidos**: Mismos que Diffuse Albedo
- **Contenido**: RGB = Albedo especular o F0

#### Normals (`pInNormals`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Normales en world-space
- **Resolución**: Render resolution
- **Formatos válidos**:
  - `VK_FORMAT_R16G16B16A16_SFLOAT` (RECOMENDADO)
  - `VK_FORMAT_R8G8B8A8_UNORM`
  - `VK_FORMAT_R10G10B10A2_UNORM_PACK32`
- **Contenido**: 
  - RGB: Normal XYZ (world-space, normalizado)
  - A: Roughness (si `InRoughnessMode = Packed`)

#### Roughness (`pInRoughness`)
- **Tipo**: `NVSDK_NGX_Resource_VK*`
- **Descripción**: Buffer de roughness separado
- **Requerido solo si**: `InRoughnessMode = Unpacked`
- **Resolución**: Render resolution
- **Formatos válidos**: `VK_FORMAT_R8_UNORM`, `VK_FORMAT_R16_SFLOAT`

### 2.3 Parámetros Temporales

#### Jitter Offsets (`InJitterOffsetX`, `InJitterOffsetY`)
- **Tipo**: `float`
- **Rango válido**: [-0.5, 0.5]
- **Unidades**: Píxeles en espacio de render
- **Descripción**: Offset sub-píxel del jitter de cámara

#### Reset (`InReset`)
- **Tipo**: `int`
- **Valores**: 0 = continuar, 1 = resetear historia temporal
- **Uso**: Establecer a 1 en cambios de escena completos

#### Frame Time Delta (`InFrameTimeDeltaInMsec`)
- **Tipo**: `float`
- **Unidades**: Milisegundos
- **Descripción**: Tiempo entre frames para estabilidad temporal

#### Render Subrect Dimensions (`InRenderSubrectDimensions`)
- **Tipo**: `NVSDK_NGX_Dimensions`
- **Contenido**: Ancho y alto del área de render

### 2.4 Parámetros Opcionales Avanzados

| Parámetro | Tipo | Descripción |
|-----------|------|-------------|
| `pInExposureTexture` | Resource | Textura de exposición |
| `pInBiasCurrentColorMask` | Resource | Máscara de bias |
| `pInTransparencyMask` | Resource | Máscara de transparencia |
| `InMVScaleX/Y` | float | Escala de motion vectors (default: 1.0) |
| `InPreExposure` | float | Pre-exposure value |
| `InExposureScale` | float | Escala de exposición |
| `pInWorldToViewMatrix` | float* | Matriz World->View 4x4 |
| `pInViewToClipMatrix` | float* | Matriz View->Clip 4x4 |

### 2.5 Parámetros de Research (Opcionales)

Estos parámetros son para investigación avanzada:

| Parámetro | Descripción |
|-----------|-------------|
| `pInReflectedAlbedo` | Albedo reflejado |
| `pInColorBeforeParticles` | Color antes de partículas |
| `pInColorBeforeTransparency` | Color antes de transparencia |
| `pInColorBeforeFog` | Color antes de fog |
| `pInDiffuseHitDistance` | Hit distance difuso |
| `pInSpecularHitDistance` | Hit distance especular |
| `pInDiffuseRayDirection` | Dirección de rayo difuso |
| `pInSpecularRayDirection` | Dirección de rayo especular |

---

## 3. Validaciones Requeridas

### 3.1 Resolución

```
✓ renderWidth < outputWidth (DLSSD requiere upscaling)
✓ renderWidth >= 8, renderHeight >= 8
✓ outputWidth >= 8, outputHeight >= 8
✓ (renderWidth % 8) == 0, (renderHeight % 8) == 0
✓ (outputWidth % 8) == 0, (outputHeight % 8) == 0
✓ Aspect ratio consistente entre render y output
```

### 3.2 Quality Mode

```
✓ qualityMode en rango [0, 5] (valores del SDK NGX)
✓ 0 = Performance (MaxPerf), 1 = Balanced, 2 = Quality (MaxQuality)
✓ 3 = Ultra Performance, 4 = Ultra Quality, 5 = DLAA
✓ qualityMode coincide con configuración
```

### 3.3 Jitter

```
✓ jitterX en rango [-0.5, 0.5]
✓ jitterY en rango [-0.5, 0.5]
✓ Jitter debe cambiar cada frame para anti-aliasing
```

### 3.4 Depth Type

```
✓ depthType = 0 (Linear) para ray tracing
✓ Formato de depth debe ser R32_SFLOAT para linear
```

### 3.5 Roughness Mode

```
✓ Si roughnessMode = Unpacked, pInRoughness debe ser válido
✓ Si roughnessMode = Packed, normals.w contiene roughness
```

---

## 4. Formato de Log del Validador

El validador escribe a: `run/logs/dlssd_validator.log`

### Ejemplo de Output

```
================================================================================
FRAME 1 - DLSSD Parameter Validation
Timestamp: 2026-03-26 12:00:00.000
================================================================================

--- RESOLUTION VALIDATION ---
  Render Resolution: 1280 x 720
  Output Resolution: 1920 x 1080
  Scale Factor: X=1.500, Y=1.500

--- QUALITY MODE VALIDATION ---
  Quality Mode Value: 1
  Quality Mode Name: QUALITY (0.667x)

--- DEPTH TYPE VALIDATION ---
  Depth Type Value: 0
  Depth Type Name: LINEAR (recommended for ray tracing)

--- ROUGHNESS MODE VALIDATION ---
  Roughness Mode Value: 1
  Roughness Mode Name: PACKED (in normals.w)

--- JITTER VALIDATION ---
  Jitter X: 0.250000
  Jitter Y: -0.125000
  Expected from JitterManager: X=0.250000, Y=-0.125000

--- TEMPORAL VALIDATION ---
  Reset Flag: 1
  Delta Time: 16.667 ms
  Delta Time: 0.016667 seconds
  Estimated FPS: 60.0

--- IMAGE FORMAT VALIDATION ---
  Color Image: 0x12345678
  Color Format: R16G16B16A16_SFLOAT (0x91)
  Depth Image: 0x23456789
  Depth Format: R32_SFLOAT (0x9D)
  Motion Vectors Image: 0x3456789A
  Motion Vectors Format: R16G16_SFLOAT (0x8B)
  Output Image: 0x456789AB
  Output Format: R16G16B16A16_SFLOAT (0x91)

--- G-BUFFER VALIDATION ---
  Diffuse Albedo View: 0x56789ABC
  Diffuse Albedo Format: R8G8B8A8_UNORM (0x2D)
  Specular Albedo View: 0x6789ABCD
  Specular Albedo Format: R8G8B8A8_UNORM (0x2D)
  Normals View: 0x789ABCDE
  Normals Format: R16G16B16A16_SFLOAT (0x91)
  Roughness: Packed in normals.w

--- FEATURE HANDLE VALIDATION ---
  Feature Handle: 0x1

================================================================================
VALIDATION PASSED - All parameters valid
================================================================================
```

---

## 5. Código de Integración

### 5.1 Validación Automática

La validación se ejecuta **automáticamente** cuando:
- Se inicializa DLSSD (`DLSSDProcessor.initialize()`)
- Cambian las dimensiones de renderizado

**NOTA**: La validación NO se ejecuta cada frame por razones de rendimiento.

### 5.2 Validación Manual

Para validar manualmente los parámetros durante runtime:

```java
// Usando DLSSDProcessor
DLSSDProcessor processor = ...; // instancia del processor
DLSSDParameterValidator.ValidationResult result = processor.validateParameters();

if (result != null && !result.valid) {
    for (String error : result.errors) {
        LOGGER.error("Validation Error: {}", error);
    }
}
```

O usando el validador directamente:

```java
// Obtener instancia del validador
DLSSDParameterValidator validator = DLSSDParameterValidator.getInstance();

// Crear parámetros manualmente
DLSSDParameterValidator.DLSSDParams params = new DLSSDParameterValidator.DLSSDParams();
params.renderWidth = 1280;
params.renderHeight = 720;
params.outputWidth = 1920;
params.outputHeight = 1080;
// ... establecer todos los parámetros

// Validar
DLSSDParameterValidator.ValidationResult result = validator.validateAll(params);

if (!result.valid) {
    for (String error : result.errors) {
        LOGGER.error("Validation Error: {}", error);
    }
}
```

### 5.3 Limpiar Log

```java
// Para iniciar un log nuevo
DLSSDParameterValidator validator = DLSSDParameterValidator.getInstance();
validator.clearLog();
```

### 5.4 Ver Resumen

```java
// Al cerrar la aplicación
validator.logSummary();
```

### 5.5 Ubicación del Log

El validador escribe a: `run/logs/dlssd_validator.log`

---

## 6. Troubleshooting Común

### Error: "Native resolution mode not supported"
- **Causa**: renderWidth == outputWidth
- **Solución**: Usar quality mode con upscaling (Quality, Balanced, etc.)

### Error: "Jitter out of range"
- **Causa**: Jitter fuera de [-0.5, 0.5]
- **Solución**: Verificar cálculo de jitter en JitterManager

### Error: "Depth format mismatch"
- **Causa**: Usar depth HW con formato incorrecto
- **Solución**: Para ray tracing, usar R32_SFLOAT con depthType=Linear

### Error: "Roughness view is null"
- **Causa**: roughnessMode=Unpacked pero no hay roughness view
- **Solución**: Cambiar a Packed mode o proporcionar roughness texture

---

## 7. Referencias

- NVIDIA NGX SDK Documentation
- DLSS Ray Reconstruction Programming Guide
- `dlss_bridge/dlss_wrapper.cpp` - Implementación del bridge nativo
- `src/main/java/me/cortex/vulkanite/client/rendering/DLSSDProcessor.java`
- `src/main/java/me/cortex/vulkanite/client/rendering/DLSSDParameterValidator.java`
