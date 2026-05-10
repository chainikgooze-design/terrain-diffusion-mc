# Terrain Diffusion MC 1.20.1 [[Modrinth]](https://modrinth.com/mod/terrain-diffusion)

#### UPDATE: The research behind this mod has been accepted to SIGGRAPH 2026, the world's premier graphics conference! That means the research was officially peer reviewed and recognized as a significant contribution to the field. Enjoy the mod!

## Which version should I use?

Three runtime builds are available on the [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) page.

**The CPU build is slow unless you are on macOS.**

| Build                     | Supports                    | Setup required                          |
|---------------------------|-----------------------------|-----------------------------------------|
| **Windows** (recommended) | Windows with any modern GPU | None                                    |
| **CUDA**                  | NVIDIA GPUs                 | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **CPU**                   | Everything else             | None                                    |

> **Mac users:** the CPU build automatically uses CoreML for hardware acceleration on Apple Silicon. No extra setup is needed.

Use the `-cuda` build only if you are on Linux, or have an NVIDIA GPU and prefer CUDA. It may improve performance, but it requires a correct CUDA and cuDNN installation.

## Requirements

- Minecraft **1.20.1**
- Java **17**
- One supported loader:
  - [Fabric Loader](https://fabricmc.net/) with the [Fabric API Mod](https://modrinth.com/mod/fabric-api)
  - Forge **47.x** for Minecraft 1.20.1
- Windows with a GPU OR Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM needed: about 1.5 GB
- RAM needed: about 2.5 GB. You may need to increase Minecraft's RAM allocation.

## Usage

**If using the CUDA build:** first see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Download the correct mod jar from [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases):
   - Fabric jar for Fabric
   - Forge jar for Forge
   - Matching runtime variant: `windows`, `cuda`, or `cpu`
2. Place the jar in your Minecraft `mods/` folder.
3. Make sure the Minecraft version is **1.20.1**.
4. Launch Minecraft at least once while online to download the models. The model download is about 2.5 GB.
5. Create a world and select the **Terrain Diffusion** world type.
6. Click **Customize** to set the `World Scale` value. See [Per-world settings](#per-world-settings).
7. The mod searches for a land spawn point near the world origin automatically. If the area around `(0, 0)` is entirely ocean, this may take a moment. Use `/td-explore` to scout the world further.

## Exploring the World

The mod includes a built-in terrain explorer web UI.

Run the `/td-explore` command in-game. It prints a clickable local link, for example:

```text
http://localhost:19801
```

Open the link in your browser to use the interactive map.

Click the map on the left to open a detailed view. Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

Use the explorer to scout continents, mountains, islands, and other interesting terrain before travelling in Minecraft.

## Configuration

Edit `config/terrain-diffusion-mc.properties`.

The file is created automatically on first launch.

```properties
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128
```

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale`, integer `1..6`

This value is saved with the world save and affects:

- how many real-world meters each block represents:
  - `scale=1` means `30m/block`
  - `scale=2` means `15m/block`
- world max height for newly created worlds, assuming the tallest point is 10000 real-world meters
- GPU load, because lower values make Terrain Diffusion run more often
- CPU load, because higher values produce larger world heights

`2` is recommended for a good balance of scale and playability. Use `1` for smaller, more compressed worlds. Most modern GPUs will become CPU-bottlenecked around scale `2` or `3`.

## Common Issues

### A dynamic link library (DLL) initialization routine failed

This can happen with old or incompatible Java versions.

For Minecraft 1.20.1, use a recent Java **17** runtime. Do not use Java 21 for this backport unless your launcher and mod loader setup explicitly support it.

### LoadLibrary failed with error 126

This applies to the CUDA build only.

It is typically caused by an incorrect CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

### java.lang.IllegalStateException: Failed to load terrain-diffusion models

This usually indicates an out-of-memory error. The logs should show the underlying cause.

Terrain Diffusion's models take about 2.5 GB of RAM, so make sure Minecraft has enough allocated memory.

### Forge loads but Fabric API is missing

Fabric API is only required for the Fabric build. Do not install Fabric API on Forge.

For Forge, use the Forge jar produced by the Forge build.

### Fabric says the mod is incompatible

Make sure you are using:

- Minecraft 1.20.1
- Fabric Loader compatible with Minecraft 1.20.1
- Fabric API for Minecraft 1.20.1
- the Fabric jar of this mod, not the Forge jar

**If your issue is still not resolved, please [raise it here](https://github.com/xandergos/terrain-diffusion-mc/issues/new).**

## Building from Source

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

The `-windows` build requires `libs/onnxruntime-dml.jar`, which is provided as part of the repository. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build it from source.

This 1.20.1 backport builds **Fabric** and **Forge**. It does not build NeoForge.

Build all loaders for Windows DirectML:

```bash
./gradlew buildDml
# or:
./gradlew build -PuseDml=true
```

Build all loaders for CUDA:

```bash
./gradlew buildCuda
# or:
./gradlew build -PuseCuda=true
```

Build all loaders for CPU:

```bash
./gradlew buildCpu
# or:
./gradlew build -PuseCpu=true
```

Build every variant for Fabric and Forge:

```bash
./gradlew buildAll
```

You can also build one loader and one variant directly:

```bash
./gradlew :fabric:buildDml
./gradlew :fabric:buildCuda
./gradlew :fabric:buildCpu

./gradlew :forge:buildDml
./gradlew :forge:buildCuda
./gradlew :forge:buildCpu
```

For a plain build without selecting a runtime variant explicitly:

```bash
./gradlew build
```

## Building onnxruntime with DirectML

### Requirements

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy), for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain, install **Desktop development with C++** from the Visual Studio Installer
- Visual Studio 2022 toolchain, same workload as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both Visual Studio toolchains up to date. Full details are available in the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

### Steps

Run all commands from the **Developer Command Prompt for VS 2022**.

```bat
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`.

Rename it to:

```text
onnxruntime-dml.jar
```

Then place it in:

```text
libs/
```

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is intentionally simple.

The model outputs elevation plus four climate variables. This is converted to Minecraft biomes with hand-written rules. Improving the biome classifier is the most direct way to improve terrain quality without changing the AI model itself.

The terrain diversity currently outpaces the biome diversity. There is a real opportunity to close that gap.
