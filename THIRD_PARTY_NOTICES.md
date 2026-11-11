# Third-party notices

Signature patterns in mod/src/Game.hpp are copied from BedrockTools (https://github.com/QYCottage/BedrockTools):

```
MIT License

Copyright (c) 2026 RadiantByte

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The OptiFine CTM tile layout and method rules follow the published OptiFine/MCPatcher format, and the Fusion texture layouts follow the format documented for SuperMartijn642's Fusion mod. converter/data/vanilla_26.60.json is extracted from Mojang/bedrock-samples v1.26.60.5 (blocks.json, terrain_texture.json aliases and flipbook_textures.json). converter/data/java_26.3.json pairs Java Edition 26.3 block and texture names (read from its blockstates and models) with those Bedrock names; it holds names only, no game assets.

Build 12 also bundles terrain fallback images generated from Mojang/bedrock-samples
v1.26.60.5, commit `15701fa9c13923f12662984c32c124327e40e3d3`. Only images needed by a
converted pack are included in that pack, at private paths. Mojang's repository
notice is: `(c) Mojang AB. All rights reserved.` These files are subject to the
[Minecraft End User License Agreement](https://www.minecraft.net/en-us/eula).
The source and its notice are available at
https://github.com/Mojang/bedrock-samples/blob/15701fa9c13923f12662984c32c124327e40e3d3/LICENSE.md.

CTM Forge uses the Shizuku API (https://github.com/RikkaApps/Shizuku-API, `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider`) to install its own updates through Shizuku:

```
MIT License

Copyright (c) 2021 RikkaW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Continuity default resources

Mod 1.6.0 bundles the modern default connected-texture resources from
**Continuity 3.0.1+26.3**, by **PepperCode1**, under **LGPL-3.0-only**.
Upstream: https://github.com/PepperCode1/Continuity.

The supplied JAR is identified by SHA-256
`429fb0946118e86cce8ace8b57d07e1971088fb3f8115439bb0f2f12df14f511`.
`mod/third_party/continuity-default-3.0.1+26.3.zip` retains the original modern
textures and rules. `tools/build_builtin_continuity.py` creates the Bedrock atlases,
fixes one `matchBlock` typo, restricts sandstone rules to full blocks with explicit
same-texture neighbor families, and removes Java-only activation conditions.
No Continuity Java code, Programmer Art resources or pane-culling models are used.

The generated bundle includes the original source ZIP, adapted rule sources, credit
and change notices, and both LGPL-3.0 and GPL-3.0 license texts. Adapted Continuity
resources remain LGPL-3.0-only. See
[the source and build notes](mod/assets/builtin/ctm_defaults/SOURCE.md).
Vanilla fallback images retain the Mojang notice above and are copied into private
paths; they do not override unrelated vanilla textures.
