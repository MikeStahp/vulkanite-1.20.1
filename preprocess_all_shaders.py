#!/usr/bin/env python3
"""
Batch GLSL Shader Preprocessor
Preprocesses all ray tracing shaders and outputs them to a single file or separate files.
"""

import os
import sys
from pathlib import Path
from preprocess_shader import GLSLPreprocessor

def main():
    base_path = "run/shaderpacks/VulkaniteRT/shaders"
    output_dir = "preprocessed_shaders"
    
    # Create output directory
    os.makedirs(output_dir, exist_ok=True)
    
    # List of all ray tracing shaders
    shaders = [
        "ray0.rgen",
        "ray0_0.rmiss",
        "ray0_1.rmiss",
        "ray0_0.rchit",
        "ray0_0.rahit",
    ]
    
    # Also create a combined output with all shaders
    combined_output = []
    combined_output.append("// " + "=" * 70)
    combined_output.append("// VULKANITE RT SHADER PACK - PREPROCESSED OUTPUT")
    combined_output.append("// " + "=" * 70)
    combined_output.append("")
    
    for shader in shaders:
        shader_path = Path(base_path) / shader
        if not shader_path.exists():
            print(f"Skipping {shader} - not found")
            continue
        
        print(f"Processing {shader}...")
        
        preprocessor = GLSLPreprocessor(base_path)
        preprocessor.process_file(shader_path)
        
        # Generate clean output
        clean_output = preprocessor.generate_output(clean=True)
        
        # Save individual file
        output_file = Path(output_dir) / f"{shader}.preprocessed"
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(clean_output)
        print(f"  -> {output_file}")
        
        # Add to combined output
        combined_output.append(f"// {'=' * 70}")
        combined_output.append(f"// SHADER: {shader}")
        combined_output.append(f"// {'=' * 70}")
        combined_output.append("")
        combined_output.append(clean_output)
        combined_output.append("")
    
    # Save combined output
    combined_file = Path(output_dir) / "all_shaders_combined.txt"
    with open(combined_file, 'w', encoding='utf-8') as f:
        f.write('\n'.join(combined_output))
    print(f"\nCombined output saved to: {combined_file}")
    
    print("\nDone!")

if __name__ == "__main__":
    main()
