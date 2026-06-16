#ifndef SKY_GLSL
#define SKY_GLSL 1

#ifndef SUNSET_WARMTH
#define SUNSET_WARMTH 0.72
#endif
#ifndef SKY_RAYLEIGH_SCALE
#define SKY_RAYLEIGH_SCALE 0.9
#endif

// ============================================================================
// Procedural Sky and Atmospheric Scattering Functions
// ============================================================================

// Approximate Rayleigh scattering phase function
float rayleighPhase(float cosTheta) {
    return 0.75 * (1.0 + cosTheta * cosTheta);
}

// Approximate Mie scattering phase function (Henyey-Greenstein)
float miePhase(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * 3.14159 * denom * sqrt(denom));
}

float skyHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float skyNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = skyHash(vec3(i, 0.0));
    float b = skyHash(vec3(i + vec2(1.0, 0.0), 0.0));
    float c = skyHash(vec3(i + vec2(0.0, 1.0), 0.0));
    float d = skyHash(vec3(i + vec2(1.0), 0.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float skyFbm(vec2 p) {
    float value = 0.0;
    float weight = 0.55;
    mat2 rotation = mat2(0.80, -0.60, 0.60, 0.80);
    for (int octave = 0; octave < 4; octave++) {
        value += skyNoise(p) * weight;
        p = rotation * p * 2.03 + vec2(17.1, 9.2);
        weight *= 0.5;
    }
    return value;
}

// Compute sun color temperature based on elevation angle
// Low sun = warm (sunset/sunrise), high sun = neutral white
vec3 getSunColorFromElevation(vec3 sunDir, vec3 baseSunColor) {
    float sunElevation = sunDir.y; // -1 (below horizon) to +1 (zenith)
    
    // Sunset/sunrise warmth: stronger when sun is near horizon
    float sunsetFactor = 1.0 - smoothstep(0.0, 0.3, sunElevation);
    
    // Warm sunset tint (orange-red)
    vec3 sunsetTint = vec3(1.0, 0.5, 0.2);
    // Neutral daylight tint
    vec3 daylightTint = vec3(1.0, 0.98, 0.95);
    
    vec3 colorTint = mix(daylightTint, sunsetTint, sunsetFactor * sunsetFactor * SUNSET_WARMTH);
    
    // Reduce intensity near horizon (atmospheric extinction)
    float atmosphericExtinction = smoothstep(-0.05, 0.15, sunElevation);
    
    return baseSunColor * colorTint * max(atmosphericExtinction, 0.05);
}

// Procedural sky color with atmospheric scattering approximation
vec3 getSkyColor(vec3 dir, vec3 sunDir, vec3 moonDir, vec3 sunColor, float time) {
    float sunElevation = sunDir.y;
    float viewElevation = dir.y;
    
    // --- Base sky gradient ---
    // Zenith color shifts based on sun position (bluer at noon, darker at sunset)
    vec3 zenithDay   = vec3(0.12, 0.32, 0.85);
    vec3 zenithDusk  = vec3(0.08, 0.12, 0.35);
    vec3 zenithNight = vec3(0.005, 0.008, 0.02);
    
    float dayFactor = smoothstep(-0.1, 0.3, sunElevation);
    float duskFactor = smoothstep(-0.15, 0.05, sunElevation) * (1.0 - smoothstep(0.05, 0.25, sunElevation));
    
    vec3 zenith = mix(zenithNight, zenithDay, dayFactor)
        + zenithDusk * duskFactor * 0.35;
    
    // Horizon color: warm during sunset, blue-white during day
    vec3 horizonDay  = vec3(0.5, 0.7, 0.95);
    vec3 horizonDusk = vec3(0.8, 0.4, 0.15);
    vec3 horizonNight = vec3(0.01, 0.015, 0.03);
    
    vec3 horizon = mix(horizonNight, horizonDay, dayFactor) + horizonDusk * duskFactor * 1.5;
    
    // Ground color (below horizon)
    vec3 ground = mix(vec3(0.005, 0.005, 0.008), vec3(0.12, 0.12, 0.1), dayFactor);
    
    // Build sky gradient
    float t = max(viewElevation * 0.5 + 0.5, 0.0);
    vec3 skyBase;
    if (viewElevation < 0.0) {
        // Below horizon: fade to ground
        skyBase = mix(ground, horizon, max(1.0 + viewElevation * 4.0, 0.0));
    } else {
        // Above horizon: smooth gradient to zenith
        skyBase = mix(horizon, zenith, pow(t, 0.5));
    }
    
    // --- Atmospheric scattering ---
    float cosTheta = dot(dir, sunDir);
    
    // Rayleigh scattering (blue sky)
    float rayleigh = rayleighPhase(cosTheta);
    vec3 rayleighColor = vec3(0.15, 0.35, 0.8) * rayleigh * dayFactor * 0.08 * SKY_RAYLEIGH_SCALE;
    
    // Mie scattering (sun halo, forward scattering)
    float mie = miePhase(cosTheta, 0.76);
    vec3 mieColor = sunColor * mie * 0.015;
    
    // Sun disk (sharp bright core)
    float sunDisk = pow(max(cosTheta, 0.0), 512.0) * 2.0;
    // Sun glow (soft halo around sun)
    float sunGlow = pow(max(cosTheta, 0.0), 8.0) * 0.15;
    // Horizon glow during sunset
    float horizonGlow = pow(max(cosTheta, 0.0), 3.0) * duskFactor * 0.3 * max(1.0 - abs(viewElevation) * 3.0, 0.0);
    
    vec3 sunContrib = sunColor * (sunDisk + sunGlow + horizonGlow);

    vec3 sky = skyBase + rayleighColor + mieColor + sunContrib;

#if SKY_CLOUDS == 1
    if (viewElevation > 0.025) {
        vec2 cloudUv = dir.xz / max(viewElevation, 0.06);
        cloudUv = cloudUv * 0.075
            + vec2(time * SKY_CLOUD_SPEED, time * SKY_CLOUD_SPEED * 0.37);
        float cloudNoise = skyFbm(cloudUv);
        float cloud = smoothstep(SKY_CLOUD_COVERAGE, SKY_CLOUD_COVERAGE + 0.16, cloudNoise);
        cloud *= smoothstep(0.025, 0.16, viewElevation);

        float cloudSun = clamp(dot(normalize(vec3(dir.x, 0.28, dir.z)), sunDir) * 0.5 + 0.5, 0.0, 1.0);
        vec3 cloudDay = mix(vec3(0.22, 0.25, 0.31), vec3(1.0, 0.95, 0.86), cloudSun);
        vec3 cloudNight = vec3(0.018, 0.022, 0.035);
        vec3 cloudColor = mix(cloudNight, cloudDay, dayFactor);
        float silverLining = smoothstep(0.55, 0.9, cloudNoise)
            * pow(max(cosTheta, 0.0), 12.0) * dayFactor;
        sky = mix(sky, cloudColor + sunColor * silverLining * 0.18, cloud * 0.82);
    }
#endif

#if SKY_STARS == 1
    float nightFactor = 1.0 - smoothstep(-0.12, 0.12, sunElevation);
    if (nightFactor > 0.001 && viewElevation > 0.0) {
        vec3 starCell = floor(normalize(dir) * 720.0);
        float starSeed = skyHash(starCell);
        float star = smoothstep(0.9965, 0.9998, starSeed);
        star *= 0.75 + 0.25 * sin(time * 1.7 + starSeed * 41.0);
        star *= smoothstep(0.02, 0.22, viewElevation) * nightFactor;
        sky += vec3(0.72, 0.82, 1.0) * star * 1.8;
    }
#endif

    float moonDot = dot(dir, normalize(moonDir));
    float moonDisk = smoothstep(cos(0.010), cos(0.006), moonDot);
    float moonHalo = pow(max(moonDot, 0.0), 192.0) * 0.08;
    sky += vec3(0.62, 0.70, 0.92) * (moonDisk * 1.6 + moonHalo)
        * (1.0 - dayFactor);

    return max(sky, vec3(0.0));
}

#endif // SKY_GLSL
