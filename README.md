# Actually Connected Textures

LeviLauncher native connected-textures mod for Minecraft Bedrock ARM64.

## Repository setup

The source archive has not yet been committed. Upload the **contents** of `connected-textures/` from `connected-textures-source.zip` to the repository root, retaining `mod/`, `converter/`, `tools/`, and `convert_all.sh`.

The GitHub Actions workflow runs C++ host tests and Python syntax checks. To enable the native ARM64 `.levipack` build, set the GitHub Actions repository variable `PRELOADER_HEADERS_REPO` to a GitHub repository containing compatible `include/pl/memory/Hook.hpp` and `include/pl/memory/Signature.hpp` headers. The Android NDK is supplied by the GitHub-hosted runner.

The native mod targets Minecraft Bedrock 1.26.60; hooks must be validated before using it with other versions.
