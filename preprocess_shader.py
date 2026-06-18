#!/usr/bin/env python3
"""
GLSL Shader Preprocessor Simulator
Simulates the C preprocessor for GLSL shaders, expanding #include directives
and tracking line numbers to help debug compilation errors.
"""

import os
import re
import sys
from pathlib import Path
from typing import Dict, Set, List, Tuple

class GLSLPreprocessor:
    def __init__(self, base_path: str):
        self.base_path = Path(base_path)
        self.included_files: Set[str] = set()
        self.output_lines: List[Tuple[int, str, str]] = []  # (output_line, source_file, source_line)
        self.defines: Dict[str, str] = {}
        self.output_line = 1

    def resolve_include(self, include_path: str, current_file: Path) -> Path:
        """Resolve an include path relative to the base path."""
        # Handle both <path> and "path" styles
        match = re.match(r'[<"](.+)[>"]', include_path)
        if match:
            include_path = match.group(1)

        # Remove leading slash if present (absolute include from base)
        if include_path.startswith('/'):
            include_path = include_path[1:]

        return self.base_path / include_path

    def process_file(self, filepath: Path, source_line: int = 1) -> None:
        """Process a single file, expanding includes and tracking lines."""
        filepath_str = str(filepath)

        if filepath_str in self.included_files:
            # Skip already included files (simple include guard simulation)
            return

        if not filepath.exists():
            print(f"// WARNING: Include file not found: {filepath}", file=sys.stderr)
            return

        self.included_files.add(filepath_str)

        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            lines = f.readlines()

        for line_num, line in enumerate(lines, 1):
            stripped = line.strip()

            # Check for #include directive
            if stripped.startswith('#include'):
                # Extract include path
                match = re.match(r'#include\s+([<"].+[>"])', stripped)
                if match:
                    include_path = match.group(1)
                    resolved = self.resolve_include(include_path, filepath)

                    # Add a comment showing the include
                    self.output_lines.append((
                        self.output_line,
                        f"{filepath.name}:{line_num}",
                        f"// === BEGIN INCLUDE: {include_path} ==="
                    ))
                    self.output_line += 1

                    # Process the included file
                    self.process_file(resolved, 1)

                    self.output_lines.append((
                        self.output_line,
                        f"{filepath.name}:{line_num}",
                        f"// === END INCLUDE: {include_path} ==="
                    ))
                    self.output_line += 1
                continue

            # Check for #define directive - track it and output it
            if stripped.startswith('#define'):
                match = re.match(r'#define\s+(\w+)(?:\s+(.*))?', stripped)
                if match:
                    name = match.group(1)
                    value = match.group(2) if match.group(2) else ""
                    self.defines[name] = value
                # Output the #define line as-is
                self.output_lines.append((
                    self.output_line,
                    f"{filepath.name}:{line_num}",
                    stripped
                ))
                self.output_line += 1
                continue

            # All other preprocessor directives (#if, #ifdef, #ifndef, #else, #elif, #endif)
            # are passed through as-is for the shader compiler to handle
            # Just output them normally
            self.output_lines.append((
                self.output_line,
                f"{filepath.name}:{line_num}",
                line.rstrip('\n\r')
            ))
            self.output_line += 1

    def generate_output(self, clean: bool = False) -> str:
        """Generate the preprocessed output.

        Args:
            clean: If True, output only the GLSL code without line annotations.
                   If False, output with line number annotations.
        """
        max_line_width = len(str(self.output_line))

        output = []

        if not clean:
            output.append("// " + "=" * 70)
            output.append("// PREPROCESSED SHADER OUTPUT")
            output.append("// Format: OUTPUT_LINE | SOURCE_FILE:SOURCE_LINE | CODE")
            output.append("// " + "=" * 70)
            output.append("")

        for out_line, source, code in self.output_lines:
            if clean:
                # Output only the code, without annotations
                output.append(code)
            else:
                # Format: line_number | source | code
                line_str = str(out_line).rjust(max_line_width)
                output.append(f"/* {line_str} */ /* {source} */ {code}")

        return '\n'.join(output)

    def find_line_in_output(self, target_line: int) -> Tuple[int, str, str]:
        """Find the source location for a given output line number."""
        for out_line, source, code in self.output_lines:
            if out_line == target_line:
                return (out_line, source, code)
        return (target_line, "unknown", "")


def main():
    if len(sys.argv) < 2:
        print("Usage: python preprocess_shader.py <shader_file> [base_path] [output_file] [--clean]")
        print("Example: python preprocess_shader.py ray0.rgen run/shaderpacks/VulkaniteRT/shaders")
        print("Example: python preprocess_shader.py ray0.rgen run/shaderpacks/VulkaniteRT/shaders preprocessed_output.txt")
        print("Example: python preprocess_shader.py ray0.rgen run/shaderpacks/VulkaniteRT/shaders preprocessed_output.txt --clean")
        print("")
        print("Options:")
        print("  --clean    Output only GLSL code without line number annotations")
        sys.exit(1)

    # Parse arguments
    args = sys.argv[1:]
    clean_mode = '--clean' in args
    if clean_mode:
        args.remove('--clean')

    shader_file = args[0]
    base_path = args[1] if len(args) > 1 else os.path.dirname(shader_file)
    output_file = args[2] if len(args) > 2 else None

    shader_path = Path(shader_file)
    if not shader_path.is_absolute():
        shader_path = Path(base_path) / shader_file

    preprocessor = GLSLPreprocessor(base_path)
    preprocessor.process_file(shader_path)

    # Generate output
    output = preprocessor.generate_output(clean=clean_mode)

    if output_file:
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(output)
        print(f"Output written to: {output_file}", file=sys.stderr)
    else:
        print(output)

    # Print summary
    print("\n// " + "=" * 70, file=sys.stderr)
    print(f"// Total output lines: {preprocessor.output_line}", file=sys.stderr)
    print(f"// Files processed: {len(preprocessor.included_files)}", file=sys.stderr)
    for f in preprocessor.included_files:
        print(f"// - {f}", file=sys.stderr)
    print("// " + "=" * 70, file=sys.stderr)


if __name__ == "__main__":
    main()
