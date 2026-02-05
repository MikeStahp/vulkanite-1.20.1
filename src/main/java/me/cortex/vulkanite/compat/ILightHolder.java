package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.client.rendering.Light;
import java.util.List;

public interface ILightHolder {
    void setLights(List<Light> lights);
    List<Light> getLights();
}
