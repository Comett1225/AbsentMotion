# Third-party notices

AbsentMotion is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`). It contains, links against,
bundles or downloads the third-party software listed below. Each entry names the component, the version the build used
on 2026-10-18 (AbsentMotion 2.1.0), its licence, its copyright line, where to find it and where AbsentMotion uses it. Copyright lines are
copied from the licence files that ship with the vendored or checked-out sources; where AbsentMotion has no such
file the entry says "see upstream" instead of guessing.

Full licence texts for the C and C++ libraries are in the appendix at the end of this file. The Apache License 2.0 text
that applies to the Apache-2.0 components is the file `LICENSE` in the root of the repository.

This list was written by hand on 2026-10-18 from the build files and the vendored sources. It is not generated. When a
dependency is added, removed or upgraded, update this file in the same change.

**Contents**

1. Engine libraries (C and C++): Skia and the libraries it links, whisper.cpp and ggml, RNNoise, LAME
2. Machine learning models: OpenAI Whisper weights (downloaded at run time), background removal model (`foss` build)
3. Kotlin and Compose libraries
4. Google Play services (proprietary)
5. Fonts, icons and effect thumbnails
6. Appendix: licence texts

---

## 1. Engine libraries

AbsentMotion's renderer is C++ on top of Skia. Skia and the libraries it needs are built from source (see
`docs/BUILD.md`) and linked statically into the engine library, `libamengine.so`. Speech recognition lives in a second
library, `libwhisper_jni.so`, loaded only when captions are generated.

### Skia

- Version: branch `chrome/m156`, commit `95ee33d7ec7791ab9c071675710a14fe6408ae4f`, with one local patch,
  `tools/skia-patches/vulkan-dst-read-copy.patch` (a change to the Vulkan back end of Skia's Graphite renderer; it is
  applied to the checkout by `tools/build_skia.sh`).
- Licence: BSD-3-Clause (text in appendix A.1).
- Copyright: Copyright (c) 2011 Google Inc. All rights reserved.
- URL: https://skia.org/ and https://skia.googlesource.com/skia
- Used in: the engine library (Graphite and Ganesh on Vulkan, Ganesh on OpenGL ES).
- The Skia checkout is not stored in this repository (`third_party/skia/` is ignored); `tools/build_skia.sh` fetches it.

### Libraries linked into Skia's build

These come from Skia's own pinned dependencies (`third_party/skia/DEPS`) and are linked into the engine library
together with Skia.

#### FreeType

- Version: 2.14.2 (the copy in Skia's dependency set).
- Licence: the FreeType Licence (FTL). FreeType is dual licensed (FTL or GPL-2.0-or-later); AbsentMotion uses it under
  the FTL. Text in appendix A.2.
- Copyright: the FTL asks for this credit line, reproduced here as it requires: "Portions of this software are
  copyright © 1996-2026 The FreeType Project (https://freetype.org). All rights reserved."
- URL: https://freetype.org/
- Used in: the engine library (font rasterisation).

#### HarfBuzz

- Version: 13.1.0.
- Licence: the "Old MIT" licence (text, with every copyright line, in appendix A.3).
- Copyright: Copyright © 2010-2022 Google, Inc. and the other holders listed in appendix A.3.
- URL: https://github.com/harfbuzz/harfbuzz
- Used in: the engine library (text shaping through Skia's SkShaper).

#### ICU

- Version: 78.2 (Chromium's copy of ICU).
- Licence: Unicode License v3 (text in appendix A.4).
- Copyright: Copyright © 2016-2025 Unicode, Inc.
- URL: https://github.com/unicode-org/icu
- Used in: the engine library (line breaking, bidirectional text and Unicode properties through Skia's SkUnicode).

#### libpng

- Version: 1.6.56.
- Licence: PNG Reference Library License version 2 (text in appendix A.5).
- Copyright: Copyright (c) 1995-2026 The PNG Reference Library Authors; Copyright (c) 2018-2026 Cosmin Truta;
  Copyright (c) 2000-2002, 2004, 2006-2018 Glenn Randers-Pehrson; Copyright (c) 1996-1997 Andreas Dilger;
  Copyright (c) 1995-1996 Guy Eric Schalnat, Group 42, Inc.
- URL: http://www.libpng.org/pub/png/libpng.html
- Used in: the engine library (PNG decoding and encoding).

#### libjpeg-turbo

- Version: 3.1.0.
- Licence: IJG licence, Modified (3-clause) BSD licence and zlib licence (texts in appendix A.6).
- Copyright: Copyright (C) 1991-2020 Thomas G. Lane, Guido Vollbeding (IJG); Copyright (C)2009-2024 D. R. Commander;
  Copyright (C)2015 Viktor Szathmáry.
- **This software is based in part on the work of the Independent JPEG Group.**
- URL: https://libjpeg-turbo.org/
- Used in: the engine library (JPEG decoding and encoding).

#### libwebp

- Version: 1.4.0.
- Licence: BSD-3-Clause (text in appendix A.7). The upstream project also grants a patent licence in its `PATENTS` file.
- Copyright: Copyright (c) 2010, Google Inc. All rights reserved.
- URL: https://chromium.googlesource.com/webm/libwebp
- Used in: the engine library (WebP decoding and encoding).

#### Wuffs

- Version: the `wuffs-mirror-release-c` revision `e3f919ccfe3ef542cfc983a82146070258fb57f8` pinned by Skia's `DEPS`.
- Licence: Apache License 2.0 (the text is `LICENSE` in the root of this repository).
- Copyright: see upstream. The licence file in the vendored copy has the unfilled Apache appendix boilerplate.
- URL: https://github.com/google/wuffs
- Used in: the engine library (image decoding through Skia).

#### zlib

- Version: 1.3.0.1 (Chromium's fork of zlib).
- Licence: the zlib licence (text in appendix A.8).
- Copyright: Copyright (C) 1995-2022 Jean-loup Gailly and Mark Adler.
- URL: https://zlib.net/
- Used in: the engine library.

#### Expat

- Version: 2.7.4.
- Licence: MIT (text in appendix A.9).
- Copyright: Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper; Copyright (c) 2001-2025
  Expat maintainers.
- URL: https://libexpat.github.io/
- Used in: the engine library. The engine reads scene files with it (`app/src/main/cpp/xml.cpp`).

#### skcms

- Version: revision `7e711e0a4467b5844f8de27ef43b680aecc6d287` (Skia's copy).
- Licence: BSD-3-Clause (text in appendix A.10).
- Copyright: Copyright (c) 2018 Google Inc. All rights reserved.
- URL: https://skia.googlesource.com/skcms
- Used in: the engine library (colour management).

#### cpu-features

- Version: the copy in the Android NDK r30 (`sources/android/cpufeatures`).
- Licence: Apache License 2.0 (the text is `LICENSE` in the root of this repository).
- Copyright: see upstream (the Android NDK `NOTICE` file).
- URL: https://developer.android.com/ndk
- Used in: the engine library (CPU feature detection for Skia's libwebp and zlib).

#### Android NDK runtime and Vulkan headers

- Version: Android NDK r30 (30.0.16248370): the static C++ standard library (libc++) and the Vulkan headers.
- Licence: Apache License 2.0 with LLVM Exceptions (libc++); Apache License 2.0 (Vulkan headers).
- Copyright: see upstream (the Android NDK `NOTICE` file).
- URL: https://developer.android.com/ndk
- Used in: the engine library.

### whisper.cpp and ggml

- Version: 1.9.5 (vendored in `third_party/whisper.cpp`; the plan is to replace the copy with a pinned submodule).
- Licence: MIT (text in appendix A.11).
- Copyright: Copyright (c) 2023-2026 The ggml authors.
- URL: https://github.com/ggml-org/whisper.cpp
- Used in: `libwhisper_jni.so` (CPU back end), for the on-device Auto Captions feature.

### RNNoise

- Version: the copy in `third_party/rnnoise` (upstream commit not recorded).
- Licence: BSD-3-Clause (text in appendix A.12).
- Copyright: Copyright (c) 2017, Mozilla; Copyright (c) 2007-2017, Jean-Marc Valin; Copyright (c) 2005-2017,
  Xiph.Org Foundation; Copyright (c) 2003-2004, Mark Borgerding.
- URL: https://gitlab.xiph.org/xiph/rnnoise
- Used in: the engine library (the "Reduce Noise" audio action).

### LAME

- Version: 3.100, the `libmp3lame` sources and `include/lame.h` from the 3.100 release (lame-3.100.tar.gz, SHA-256
  `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`), vendored in `third_party/lame` together with its
  `COPYING`, `LICENSE` and `README`. One change: in `libmp3lame/util.c` the Linux floating-point control block is skipped on
  Android (`&& !defined(__ANDROID__)`), and two lines lost trailing spaces. All other files are unchanged.
- Licence: GNU Library General Public License, version 2 or later (LGPL; text in appendix A.14).
- Copyright: Copyright (c) 1999-2017 Mike Cheng, Mark Taylor, Robert Hegemann, Takehiro Tominaga, Gabriel Bouvigne,
  Alexander Leidinger, Naoki Shibata, Albert L. Faber and the other LAME authors named in the source file headers.
- URL: https://lame.sourceforge.io/
- Used in: MP3 audio export. LAME is built as its own shared library, `libmp3lame.so`, and loaded through a small
  separate library, `liblame_jni.so`, so it can be replaced with another build of LAME.

---

## 2. Machine learning models

### OpenAI Whisper model weights (ggml conversion)

- Version: `ggml-base-q5_1.bin` (about 60 MB).
- Licence: MIT (the OpenAI Whisper release), converted to the ggml format by the whisper.cpp project.
- Copyright: see upstream (https://github.com/openai/whisper).
- URL: https://huggingface.co/ggerganov/whisper.cpp
- Used in: Auto Captions. The model is **not** part of the app. It is downloaded the first time a user generates
  captions, after the app has shown its size and asked for confirmation, and it can be removed again in Settings.

### Background removal model (`foss` build only)

- Version: `app/src/foss/assets/absent_bg.abg` (2,943,735 bytes, SHA-256
  `9bfbfcba996eb0b0b4f5da03d7dcbce704f5b8810101eb92f62b9e6d44465439`), a small segmentation network trained for
  AbsentMotion; weights stored as 8-bit integers and expanded to 32-bit floats on load. Its inference code,
  `app/src/main/cpp/absent_bg/`, is part of AbsentMotion.
- Licence: Apache-2.0 ("Original student architectures, training scripts and C++ runtime: Apache License 2.0."),
  subject to the source notices and the CC BY attribution below.
- Copyright: The AbsentMotion authors.
- Used in: "Remove Background" on images in the `foss` build. It runs on the device and nothing is downloaded. The
  `play` build uses ML Kit instead (section 4) and does not contain this file.
- Pretrained encoder: `timm/mobilenetv3_large_100.ra_in1k`, revision `96f46a1c52932f27492dff66c72378eb99b443a7`,
  Apache-2.0, by Ross Wightman and timm contributors (https://huggingface.co/timm/mobilenetv3_large_100.ra_in1k,
  https://github.com/huggingface/pytorch-image-models); MobileNetV3: Howard et al., "Searching for MobileNetV3"
  (https://arxiv.org/abs/1905.02244). ImageNet-pretrained encoder only; classifier discarded; no ImageNet images
  fetched. The Apache-2.0 text is `LICENSE` in the root of this repository.
- Training pictures: 18,615 photographs under Creative Commons Attribution licences (18,559 CC BY 2.0,
  https://creativecommons.org/licenses/by/2.0/, and 56 CC BY 4.0, https://creativecommons.org/licenses/by/4.0/); only
  explicit CC BY records were accepted, and each licence was checked at the source before use. 8,391 come from the
  COCO 2017 training set (https://cocodataset.org) and 10,224 from Open Images V7
  (https://storage.googleapis.com/openimages/web/factsfigures_v7.html). Changes: resized; JPEG re-encoded. Image
  rights remain with individual creators. No picture is part of this repository or the app. The creator, title, source page and licence of every picture are listed in the file
  `AbsentMotion-training-image-credits.txt`, attached to each GitHub release that includes the model
  (https://github.com/Comett1225/AbsentMotion/releases/latest/download/AbsentMotion-training-image-credits.txt); it is
  not part of this repository.
- COCO annotations: training/ground-truth masks are based on COCO Consortium annotations under CC BY 4.0 (union of all
  annotated instances, resized). Citation: Tsung-Yi Lin et al., Microsoft COCO: Common Objects in Context, ECCV 2014.
- Open Images labels and metadata: Google LLC, CC BY 4.0; Kuznetsova et al., The Open Images Dataset V4, IJCV 2020.
- Teacher: the training masks were made by BiRefNet_lite, revision `aa62cd87eafb9cc43056d08ef3615a14628b831d`
  (https://huggingface.co/ZhengPeng7/BiRefNet_lite), MIT License, Copyright (c) 2024 ZhengPeng. The teacher only made
  labels: none of its weights or code are in AbsentMotion. Its card states training on DIS-TR; no DIS images were
  used.

---

## 3. Kotlin and Compose libraries

Versions are the ones the build resolved on 2026-10-18. The Apache-2.0 text is `LICENSE` in the root of this
repository. For all Apache-2.0 entries below the copyright holders are named in the projects' own repositories ("see
upstream"). The artifacts were not inspected for a `META-INF/NOTICE` file.

### AndroidX Compose, Activity Compose and Material 3

- Version: Compose BOM 2026.09.00 (`androidx.compose.ui`, `foundation`, `material3`), `activity-compose` 1.13.0.
- Licence: Apache-2.0.
- Copyright: see upstream.
- URL: https://developer.android.com/jetpack/androidx
- Used in: the app's user interface.

### CameraX and the AndroidX libraries it needs

- Version: CameraX 1.5.3 (`androidx.camera:camera-camera2` and `camera-view`, which bring `camera-core`, `camera-lifecycle`,
  `camera-video` and `androidx.camera.featurecombinationquery:featurecombinationquery` 1.5.3). CameraX also adds these AndroidX
  libraries that the build did not contain before: `appcompat` and `appcompat-resources` 1.1.0, `fragment` 1.1.0,
  `exifinterface` 1.4.2, `lifecycle-livedata` and `lifecycle-livedata-core-ktx` 2.9.4, `vectordrawable` and
  `vectordrawable-animated` 1.1.0, `viewpager`, `loader`, `drawerlayout`, `cursoradapter` and `customview` 1.0.0,
  `concurrent-futures-ktx` 1.1.0 and `tracing-ktx` 1.2.0, plus Google's `com.google.auto.value:auto-value-annotations` 1.6.3.
- Licence: Apache-2.0 (all of them).
- Copyright: see upstream.
- URL: https://developer.android.com/jetpack/androidx/releases/camera and https://github.com/google/auto
- Used in: the in-app camera (photo and video capture from the Add menu and from the new project sheet).

### kotlinx.coroutines

- Version: 1.9.0 (`kotlinx-coroutines-core` and `kotlinx-coroutines-android`, dependencies of Compose).
- Licence: Apache-2.0.
- Copyright: see upstream.
- URL: https://github.com/Kotlin/kotlinx.coroutines
- Used in: the app.

### Kotlin standard library

- Version: 2.4.21 (the Kotlin Gradle plugin version, see `docs/BUILD.md`).
- Licence: Apache-2.0.
- Copyright: see upstream.
- URL: https://github.com/JetBrains/kotlin
- Used in: the app.

### kotlinx.serialization

- Version: 1.7.3 (`kotlinx-serialization-core-jvm`, a dependency of AndroidX libraries).
- Licence: Apache-2.0.
- Copyright: see upstream.
- URL: https://github.com/Kotlin/kotlinx.serialization
- Used in: the app.

### JetBrains annotations

- Version: 23.0.0 (`org.jetbrains:annotations`, a dependency of the Kotlin standard library).
- Licence: Apache-2.0.
- Copyright: see upstream.
- URL: https://github.com/JetBrains/java-annotations
- Used in: the app.

### Test-only dependencies (not distributed)

JUnit 4.13.2 (Eclipse Public License 1.0), kXML 2 2.3.0 (`net.sf.kxml:kxml2`, a "BSD style" licence) and org.json
20240303 (`org.json:json`, the "JSON License") are used by the unit tests only and are not part of any build output.

---

## 4. Google Play services (proprietary)

- Components: ML Kit subject segmentation through Google Play services
  (`com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1`, used for "Remove Background" on
  images).
- Licence: proprietary (Google APIs Terms of Service and the ML Kit terms). These libraries are **not** open source.
  The segmentation model is fetched by Google Play services, not by AbsentMotion.
- Copyright: Google LLC.
- Used in: the Android app. Intended to be confined to a Google Play build flavour, with a flavour without them for
  distribution channels that accept only free software.
- The free Android build (the `foss` flavour) includes neither library; only the `play` build does.

---

## 5. Fonts, icons and effect thumbnails

### Google Fonts

AbsentMotion contains 50 font families that work without a network connection, and offers 100 more that the
app downloads from Google's font servers (`fonts.googleapis.com`, `fonts.gstatic.com`) when a user picks one. A downloaded
family keeps its own licence: the SIL Open Font License 1.1, the Apache License 2.0 or the Ubuntu Font Licence 1.0, as stated
for that family at https://fonts.google.com/. Text a user sets in any of these fonts belongs to the user.

The built-in fonts are copied from the repository https://github.com/google/fonts at commit
`47d54ca1ebb14fd864695f1d39dc734f39017c33`. The table lists, for each family, the copyright line from the font, the licence and the licence file that
ships next to the font files in the app. 35 families are modified: variable fonts were turned into static Regular and Bold
instances, and every font was cut down to the Latin, Cyrillic and Vietnamese letters and (for Hindi, Thai and Arabic fonts)
the writing system of that font, with `tools/gen_fonts.py` and fontTools. The 15 families whose copyright holders reserve
the font name (Abril Fatface, Alfa Slab One, Bitter, Comfortaa, DM Serif Display, Dancing Script, Kaushan Script, Lobster, Lora, Orbitron, Playfair Display, Press Start 2P, Quicksand, Raleway, Righteous) are included exactly as published, because the SIL Open Font License
does not allow a modified version to keep a reserved name. The licence text of the SIL Open Font License 1.1 is in
appendix A.13; the Apache License 2.0 text is the file `LICENSE`.

| What | Source | Licence | File |
|---|---|---|---|
| Abril Fatface | Copyright (c) 2011, Copyright (c) 2011, TypeTogether (www.type-together.com), with Reserved Font Names "Abril" and "Abril Fatface" | SIL Open Font License 1.1 | `assets/fonts/AbrilFatface/OFL.txt` |
| Alfa Slab One | Copyright 2016 The Alfa Slab One Project Authors (http://www.jmsole.cl / info@jmsole.cl), with Reserved Font Name "Alfa Slab". | SIL Open Font License 1.1 | `assets/fonts/AlfaSlabOne/OFL.txt` |
| Amatic SC | Copyright 2015 The Amatic SC Project Authors (https://github.com/googlefonts/AmaticSC) | SIL Open Font License 1.1 | `assets/fonts/AmaticSC/OFL.txt` |
| Anton | Copyright 2020 The Anton Project Authors (https://github.com/googlefonts/AntonFont.git) | SIL Open Font License 1.1 | `assets/fonts/Anton/OFL.txt` |
| Bangers | Copyright 2010 The Bangers Project Authors (https://github.com/googlefonts/bangers) | SIL Open Font License 1.1 | `assets/fonts/Bangers/OFL.txt` |
| Bebas Neue | Copyright 2019 The Bebas Neue Project Authors (https://github.com/dharmatype/Bebas-Neue) | SIL Open Font License 1.1 | `assets/fonts/BebasNeue/OFL.txt` |
| Bitter | Copyright 2011 The Bitter Project Authors (https://github.com/solmatas/BitterPro) | SIL Open Font License 1.1 | `assets/fonts/Bitter/OFL.txt` |
| Black Ops One | Copyright 2022 The PinyonScript Project Authors (https://github.com/SorkinType/Black-Ops) | SIL Open Font License 1.1 | `assets/fonts/BlackOpsOne/OFL.txt` |
| Bungee | Copyright 2023 The Bungee Project Authors (https://github.com/djrrb/Bungee) | SIL Open Font License 1.1 | `assets/fonts/Bungee/OFL.txt` |
| Cairo | Copyright 2009 The Cairo Project Authors (https://github.com/Gue3bara/Cairo) | SIL Open Font License 1.1 | `assets/fonts/Cairo/OFL.txt` |
| Caveat | Copyright 2014 The Caveat Project Authors (https://github.com/googlefonts/caveat) | SIL Open Font License 1.1 | `assets/fonts/Caveat/OFL.txt` |
| Cinzel | Copyright 2020 The Cinzel Project Authors (https://github.com/NDISCOVER/Cinzel) | SIL Open Font License 1.1 | `assets/fonts/Cinzel/OFL.txt` |
| Comfortaa | Copyright 2011 The Comfortaa Project Authors (https://github.com/alexeiva/comfortaa), with Reserved Font Name "Comfortaa". | SIL Open Font License 1.1 | `assets/fonts/Comfortaa/OFL.txt` |
| DM Sans | Copyright 2014 The DM Sans Project Authors (https://github.com/googlefonts/dm-fonts) | SIL Open Font License 1.1 | `assets/fonts/DMSans/OFL.txt` |
| DM Serif Display | Copyright 2014 - 2017 Adobe Systems Incorporated (http://www.adobe.com/), with Reserved Font Name 'Source'. Copyright 2019 Google LLC. | SIL Open Font License 1.1 | `assets/fonts/DMSerifDisplay/OFL.txt` |
| Dancing Script | Copyright 2016 The Dancing Script Project Authors (https://github.com/googlefonts/DancingScript), with Reserved Font Name "Dancing Script". | SIL Open Font License 1.1 | `assets/fonts/DancingScript/OFL.txt` |
| Fredoka | Copyright 2016 The Fredoka Project Authors (https://github.com/hafontia/Fredoka-One) | SIL Open Font License 1.1 | `assets/fonts/Fredoka/OFL.txt` |
| Great Vibes | Copyright 2010 The Great Vibes Pro Project Authors (https://github.com/googlefonts/great-vibes) | SIL Open Font License 1.1 | `assets/fonts/GreatVibes/OFL.txt` |
| Hind | Copyright (c) 2014 Indian Type Foundry (info@indiantypefoundry.com) | SIL Open Font License 1.1 | `assets/fonts/Hind/OFL.txt` |
| Inter | Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter) | SIL Open Font License 1.1 | `assets/fonts/Inter/OFL.txt` |
| JetBrains Mono | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) | SIL Open Font License 1.1 | `assets/fonts/JetBrainsMono/OFL.txt` |
| Kanit | Copyright 2020 The Kanit Project Authors (https://github.com/cadsondemak/kanit) | SIL Open Font License 1.1 | `assets/fonts/Kanit/OFL.txt` |
| Kaushan Script | Copyright (c) 2011, Pablo Impallari (www.impallari.com/impallari@gmail.com), Copyright (c) 2011, Igino Marini. (www.ikern.com/mail@iginomarini.com), with Reserved Font Name Kaushan Script. | SIL Open Font License 1.1 | `assets/fonts/KaushanScript/OFL.txt` |
| Lobster | Copyright 2010 The Lobster Project Authors (https://github.com/impallari/The-Lobster-Font), with Reserved Font Name "Lobster". | SIL Open Font License 1.1 | `assets/fonts/Lobster/OFL.txt` |
| Lora | Copyright 2011 The Lora Project Authors (https://github.com/cyrealtype/Lora-Cyrillic), with Reserved Font Name "Lora". | SIL Open Font License 1.1 | `assets/fonts/Lora/OFL.txt` |
| Manrope | Copyright 2019 The Manrope Project Authors (https://github.com/googlefonts/manrope) | SIL Open Font License 1.1 | `assets/fonts/Manrope/OFL.txt` |
| Montserrat | Copyright 2011 The Montserrat Project Authors (https://github.com/JulietaUla/Montserrat) | SIL Open Font License 1.1 | `assets/fonts/Montserrat/OFL.txt` |
| Mulish | Copyright 2016 The Mulish Project Authors (https://github.com/googlefonts/mulish) | SIL Open Font License 1.1 | `assets/fonts/Mulish/OFL.txt` |
| Noto Serif | Copyright 2022 The Noto Project Authors (https://github.com/notofonts/latin-greek-cyrillic) | SIL Open Font License 1.1 | `assets/fonts/NotoSerif/OFL.txt` |
| Nunito | Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito) | SIL Open Font License 1.1 | `assets/fonts/Nunito/OFL.txt` |
| Open Sans | Copyright 2020 The Open Sans Project Authors (https://github.com/googlefonts/opensans) | SIL Open Font License 1.1 | `assets/fonts/OpenSans/OFL.txt` |
| Orbitron | Copyright 2018 The Orbitron Project Authors (https://github.com/theleagueof/orbitron), with Reserved Font Name: "Orbitron". | SIL Open Font License 1.1 | `assets/fonts/Orbitron/OFL.txt` |
| Oswald | Copyright 2016 The Oswald Project Authors (https://github.com/googlefonts/OswaldFont) | SIL Open Font License 1.1 | `assets/fonts/Oswald/OFL.txt` |
| Pacifico | Copyright 2018 The Pacifico Project Authors (https://github.com/googlefonts/Pacifico) | SIL Open Font License 1.1 | `assets/fonts/Pacifico/OFL.txt` |
| Permanent Marker | Copyright (c) 2010 by Font Diner, Inc. All rights reserved. | Apache License 2.0 | `assets/fonts/PermanentMarker/LICENSE.txt` |
| Playfair Display | Copyright 2017 The Playfair Display Project Authors (https://github.com/clauseggers/Playfair-Display), with Reserved Font Name "Playfair Display". | SIL Open Font License 1.1 | `assets/fonts/PlayfairDisplay/OFL.txt` |
| Poppins | Copyright 2020 The Poppins Project Authors (https://github.com/itfoundry/Poppins) | SIL Open Font License 1.1 | `assets/fonts/Poppins/OFL.txt` |
| Press Start 2P | Copyright 2012 The Press Start 2P Project Authors (cody@zone38.net), with Reserved Font Name "Press Start 2P" | SIL Open Font License 1.1 | `assets/fonts/PressStart2P/OFL.txt` |
| Prompt | Copyright (c) 2015, Cadson Demak (info@cadsondemak.com) | SIL Open Font License 1.1 | `assets/fonts/Prompt/OFL.txt` |
| Quicksand | Copyright 2019 The Quicksand Project Authors (https://github.com/andrew-paglinawan/QuicksandFamily.git), with Reserved Font Name "Quicksand" | SIL Open Font License 1.1 | `assets/fonts/Quicksand/OFL.txt` |
| Raleway | Copyright 2010 The Raleway Project Authors (impallari@gmail.com), with Reserved Font Name "Raleway". | SIL Open Font License 1.1 | `assets/fonts/Raleway/OFL.txt` |
| Righteous | Copyright (c) 2011 by Brian J. Bonislawsky DBA Astigmatic (AOETI) (astigma@astigmatic.com), with Reserved Font Name "Righteous" | SIL Open Font License 1.1 | `assets/fonts/Righteous/OFL.txt` |
| Roboto | Copyright 2011 The Roboto Project Authors (https://github.com/googlefonts/roboto-classic) | SIL Open Font License 1.1 | `assets/fonts/Roboto/OFL.txt` |
| Roboto Mono | Copyright 2015 The Roboto Mono Project Authors (https://github.com/googlefonts/robotomono) | SIL Open Font License 1.1 | `assets/fonts/RobotoMono/OFL.txt` |
| Roboto Slab | Copyright 2018 The Roboto Slab Project Authors (https://github.com/googlefonts/robotoslab) | Apache License 2.0 | `assets/fonts/RobotoSlab/LICENSE.txt` |
| Rubik | Copyright 2015 The Rubik Project Authors (https://github.com/googlefonts/rubik) | SIL Open Font License 1.1 | `assets/fonts/Rubik/OFL.txt` |
| Satisfy | Copyright (c) 2011 by Font Diner, Inc. All rights reserved. | Apache License 2.0 | `assets/fonts/Satisfy/LICENSE.txt` |
| Space Mono | Copyright 2016 The Space Mono Project Authors (https://github.com/googlefonts/spacemono) | SIL Open Font License 1.1 | `assets/fonts/SpaceMono/OFL.txt` |
| Tajawal | (c) 2017 by Boutros International. All rights reserved. | SIL Open Font License 1.1 | `assets/fonts/Tajawal/OFL.txt` |
| Work Sans | Copyright 2019 The Work Sans Project Authors (https://github.com/weiweihuanghuang/Work-Sans) | SIL Open Font License 1.1 | `assets/fonts/WorkSans/OFL.txt` |

### Icons

The icon sets used in the user interface, and their licences, are listed in `docs/ICON_SOURCES.md`.

### Effect thumbnail photos

The effect thumbnails (`app/src/main/assets/effects/thumb/*.webp`, 192 x 192 pixels) are rendered by
`tools/effect_thumbs.py` from the six photographs below, all from Wikimedia Commons. Each author released the photograph
under CC0 1.0, a public domain dedication (https://creativecommons.org/publicdomain/zero/1.0/), so no credit is required;
they are listed for the record. The licence of each was checked on 2026-10-19 on the file's Commons page and in its
metadata (`License`: `cc0`, `Credit`: own work); the script checks it again whenever it downloads a photograph. Only the
small thumbnails ship; the photographs are not in the repository or the app. Which effect uses which photograph, the crop
and the effect settings are in `tools/effect_showcase.json`. The green and black backgrounds of the Chroma Key and Luma
Key thumbnails are drawn by the script.

- "Fishmonger smiling" by Wilfredor, own work, CC0 1.0, https://commons.wikimedia.org/wiki/File:Fishmonger_smiling.jpg
  (the page records the subject's consent and carries a personality rights notice).
- "Ruby Mountain refected in Diablo Lake" by Ron Clausen, own work, CC0 1.0,
  https://commons.wikimedia.org/wiki/File:Ruby_Mountain_refected_in_Diablo_Lake.jpg
- "Speculum Feather from Pacific Black Duck" by Hallo3800, own work, CC0 1.0,
  https://commons.wikimedia.org/wiki/File:Speculum_Feather_from_Pacific_Black_Duck.jpg
- "Sunset rolling waves" by Mingiki, own work, CC0 1.0, https://commons.wikimedia.org/wiki/File:Sunset_rolling_waves.jpg
- "Ara ararauna portrait Rhodes" by Jebulon, own work, CC0 1.0,
  https://commons.wikimedia.org/wiki/File:Ara_ararauna_portrait_Rhodes.jpg
- "Fleabane With Black Background" by Tfbybyhf, own work, CC0 1.0,
  https://commons.wikimedia.org/wiki/File:Fleabane_With_Black_Background.jpg

---

## 6. Appendix: licence texts

The texts below are copied unchanged from the files that ship with the sources named in each entry.

### A.1 Skia (BSD-3-Clause)

~~~~text
Copyright (c) 2011 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
~~~~

### A.2 FreeType License (FTL)

~~~~text
                    The FreeType Project LICENSE
                    ----------------------------

                            2006-Jan-27

                    Copyright 1996-2002, 2006 by
          David Turner, Robert Wilhelm, and Werner Lemberg



Introduction
============

  The FreeType  Project is distributed in  several archive packages;
  some of them may contain, in addition to the FreeType font engine,
  various tools and  contributions which rely on, or  relate to, the
  FreeType Project.

  This  license applies  to all  files found  in such  packages, and
  which do not  fall under their own explicit  license.  The license
  affects  thus  the  FreeType   font  engine,  the  test  programs,
  documentation and makefiles, at the very least.

  This  license   was  inspired  by  the  BSD,   Artistic,  and  IJG
  (Independent JPEG  Group) licenses, which  all encourage inclusion
  and  use of  free  software in  commercial  and freeware  products
  alike.  As a consequence, its main points are that:

    o We don't promise that this software works. However, we will be
      interested in any kind of bug reports. (`as is' distribution)

    o You can  use this software for whatever you  want, in parts or
      full form, without having to pay us. (`royalty-free' usage)

    o You may not pretend that  you wrote this software.  If you use
      it, or  only parts of it,  in a program,  you must acknowledge
      somewhere  in  your  documentation  that  you  have  used  the
      FreeType code. (`credits')

  We  specifically  permit  and  encourage  the  inclusion  of  this
  software, with  or without modifications,  in commercial products.
  We  disclaim  all warranties  covering  The  FreeType Project  and
  assume no liability related to The FreeType Project.


  Finally,  many  people  asked  us  for  a  preferred  form  for  a
  credit/disclaimer to use in compliance with this license.  We thus
  encourage you to use the following text:

   """
    Portions of this software are copyright © <year> The FreeType
    Project (https://freetype.org).  All rights reserved.
   """

  Please replace <year> with the value from the FreeType version you
  actually use.


Legal Terms
===========

0. Definitions
--------------

  Throughout this license,  the terms `package', `FreeType Project',
  and  `FreeType  archive' refer  to  the  set  of files  originally
  distributed  by the  authors  (David Turner,  Robert Wilhelm,  and
  Werner Lemberg) as the `FreeType Project', be they named as alpha,
  beta or final release.

  `You' refers to  the licensee, or person using  the project, where
  `using' is a generic term including compiling the project's source
  code as  well as linking it  to form a  `program' or `executable'.
  This  program is  referred to  as  `a program  using the  FreeType
  engine'.

  This  license applies  to all  files distributed  in  the original
  FreeType  Project,   including  all  source   code,  binaries  and
  documentation,  unless  otherwise  stated   in  the  file  in  its
  original, unmodified form as  distributed in the original archive.
  If you are  unsure whether or not a particular  file is covered by
  this license, you must contact us to verify this.

  The FreeType  Project is copyright (C) 1996-2000  by David Turner,
  Robert Wilhelm, and Werner Lemberg.  All rights reserved except as
  specified below.

1. No Warranty
--------------

  THE FREETYPE PROJECT  IS PROVIDED `AS IS' WITHOUT  WARRANTY OF ANY
  KIND, EITHER  EXPRESS OR IMPLIED,  INCLUDING, BUT NOT  LIMITED TO,
  WARRANTIES  OF  MERCHANTABILITY   AND  FITNESS  FOR  A  PARTICULAR
  PURPOSE.  IN NO EVENT WILL ANY OF THE AUTHORS OR COPYRIGHT HOLDERS
  BE LIABLE  FOR ANY DAMAGES CAUSED  BY THE USE OR  THE INABILITY TO
  USE, OF THE FREETYPE PROJECT.

2. Redistribution
-----------------

  This  license  grants  a  worldwide, royalty-free,  perpetual  and
  irrevocable right  and license to use,  execute, perform, compile,
  display,  copy,   create  derivative  works   of,  distribute  and
  sublicense the  FreeType Project (in  both source and  object code
  forms)  and  derivative works  thereof  for  any  purpose; and  to
  authorize others  to exercise  some or all  of the  rights granted
  herein, subject to the following conditions:

    o Redistribution of  source code  must retain this  license file
      (`FTL.TXT') unaltered; any  additions, deletions or changes to
      the original  files must be clearly  indicated in accompanying
      documentation.   The  copyright   notices  of  the  unaltered,
      original  files must  be  preserved in  all  copies of  source
      files.

    o Redistribution in binary form must provide a  disclaimer  that
      states  that  the software is based in part of the work of the
      FreeType Team,  in  the  distribution  documentation.  We also
      encourage you to put an URL to the FreeType web page  in  your
      documentation, though this isn't mandatory.

  These conditions  apply to any  software derived from or  based on
  the FreeType Project,  not just the unmodified files.   If you use
  our work, you  must acknowledge us.  However, no  fee need be paid
  to us.

3. Advertising
--------------

  Neither the  FreeType authors and  contributors nor you  shall use
  the name of the  other for commercial, advertising, or promotional
  purposes without specific prior written permission.

  We suggest,  but do not require, that  you use one or  more of the
  following phrases to refer  to this software in your documentation
  or advertising  materials: `FreeType Project',  `FreeType Engine',
  `FreeType library', or `FreeType Distribution'.

  As  you have  not signed  this license,  you are  not  required to
  accept  it.   However,  as  the FreeType  Project  is  copyrighted
  material, only  this license, or  another one contracted  with the
  authors, grants you  the right to use, distribute,  and modify it.
  Therefore,  by  using,  distributing,  or modifying  the  FreeType
  Project, you indicate that you understand and accept all the terms
  of this license.

4. Contacts
-----------

  There are two mailing lists related to FreeType:

    o freetype@nongnu.org

      Discusses general use and applications of FreeType, as well as
      future and  wanted additions to the  library and distribution.
      If  you are looking  for support,  start in  this list  if you
      haven't found anything to help you in the documentation.

    o freetype-devel@nongnu.org

      Discusses bugs,  as well  as engine internals,  design issues,
      specific licenses, porting, etc.

  Our home page can be found at

    https://freetype.org


--- end of FTL.TXT ---
~~~~

### A.3 HarfBuzz ("Old MIT")

~~~~text
HarfBuzz is licensed under the so-called "Old MIT" license.  Details follow.
For parts of HarfBuzz that are licensed under different licenses see individual
files names COPYING in subdirectories where applicable.

Copyright © 2010-2022  Google, Inc.
Copyright © 2015-2020  Ebrahim Byagowi
Copyright © 2019,2020  Facebook, Inc.
Copyright © 2012,2015  Mozilla Foundation
Copyright © 2011  Codethink Limited
Copyright © 2008,2010  Nokia Corporation and/or its subsidiary(-ies)
Copyright © 2009  Keith Stribley
Copyright © 2011  Martin Hosken and SIL International
Copyright © 2007  Chris Wilson
Copyright © 2005,2006,2020,2021,2022,2023  Behdad Esfahbod
Copyright © 2004,2007,2008,2009,2010,2013,2021,2022,2023  Red Hat, Inc.
Copyright © 1998-2005  David Turner and Werner Lemberg
Copyright © 2016  Igalia S.L.
Copyright © 2022  Matthias Clasen
Copyright © 2018,2021  Khaled Hosny
Copyright © 2018,2019,2020  Adobe, Inc
Copyright © 2013-2015  Alexei Podtelezhnikov

For full copyright notices consult the individual files in the package.


Permission is hereby granted, without written agreement and without
license or royalty fees, to use, copy, modify, and distribute this
software and its documentation for any purpose, provided that the
above copyright notice and the following two paragraphs appear in
all copies of this software.

IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE TO ANY PARTY FOR
DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES
ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN
IF THE COPYRIGHT HOLDER HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGE.

THE COPYRIGHT HOLDER SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING,
BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE.  THE SOFTWARE PROVIDED HEREUNDER IS
ON AN "AS IS" BASIS, AND THE COPYRIGHT HOLDER HAS NO OBLIGATION TO
PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
~~~~

### A.4 ICU (Unicode License v3)

~~~~text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 2016-2025 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

SPDX-License-Identifier: Unicode-3.0

----------------------------------------------------------------------

Third-Party Software Licenses

This section contains third-party software notices and/or additional
terms for licensed third-party software components included within ICU
libraries.

----------------------------------------------------------------------

ICU License - ICU 1.8.1 to ICU 57.1

COPYRIGHT AND PERMISSION NOTICE

Copyright (c) 1995-2016 International Business Machines Corporation and others
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, and/or sell copies of the Software, and to permit persons
to whom the Software is furnished to do so, provided that the above
copyright notice(s) and this permission notice appear in all copies of
the Software and that both the above copyright notice(s) and this
permission notice appear in supporting documentation.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF THIRD PARTY RIGHTS. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
HOLDERS INCLUDED IN THIS NOTICE BE LIABLE FOR ANY CLAIM, OR ANY
SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER
RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF
CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

Except as contained in this notice, the name of a copyright holder
shall not be used in advertising or otherwise to promote the sale, use
or other dealings in this Software without prior written authorization
of the copyright holder.

All trademarks and registered trademarks mentioned herein are the
property of their respective owners.

----------------------------------------------------------------------

Chinese/Japanese Word Break Dictionary Data (cjdict.txt)

 #     The Google Chrome software developed by Google is licensed under
 # the BSD license. Other software included in this distribution is
 # provided under other licenses, as set forth below.
 #
 #  The BSD License
 #  http://opensource.org/licenses/bsd-license.php
 #  Copyright (C) 2006-2008, Google Inc.
 #
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 # modification, are permitted provided that the following conditions are met:
 #
 #  Redistributions of source code must retain the above copyright notice,
 # this list of conditions and the following disclaimer.
 #  Redistributions in binary form must reproduce the above
 # copyright notice, this list of conditions and the following
 # disclaimer in the documentation and/or other materials provided with
 # the distribution.
 #  Neither the name of  Google Inc. nor the names of its
 # contributors may be used to endorse or promote products derived from
 # this software without specific prior written permission.
 #
 #
 #  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 # CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 # INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 # MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 # DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 # LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 # CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 # SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 # BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 # LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 # NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 # SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 #
 #
 #  The word list in cjdict.txt are generated by combining three word lists
 # listed below with further processing for compound word breaking. The
 # frequency is generated with an iterative training against Google web
 # corpora.
 #
 #  * Libtabe (Chinese)
 #    - https://sourceforge.net/project/?group_id=1519
 #    - Its license terms and conditions are shown below.
 #
 #  * IPADIC (Japanese)
 #    - http://chasen.aist-nara.ac.jp/chasen/distribution.html
 #    - Its license terms and conditions are shown below.
 #
 #  ---------COPYING.libtabe ---- BEGIN--------------------
 #
 #  /*
 #   * Copyright (c) 1999 TaBE Project.
 #   * Copyright (c) 1999 Pai-Hsiang Hsiao.
 #   * All rights reserved.
 #   *
 #   * Redistribution and use in source and binary forms, with or without
 #   * modification, are permitted provided that the following conditions
 #   * are met:
 #   *
 #   * . Redistributions of source code must retain the above copyright
 #   *   notice, this list of conditions and the following disclaimer.
 #   * . Redistributions in binary form must reproduce the above copyright
 #   *   notice, this list of conditions and the following disclaimer in
 #   *   the documentation and/or other materials provided with the
 #   *   distribution.
 #   * . Neither the name of the TaBE Project nor the names of its
 #   *   contributors may be used to endorse or promote products derived
 #   *   from this software without specific prior written permission.
 #   *
 #   * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 #   * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 #   * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 #   * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 #   * REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 #   * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 #   * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 #   * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 #   * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 #   * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 #   * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 #   * OF THE POSSIBILITY OF SUCH DAMAGE.
 #   */
 #
 #  /*
 #   * Copyright (c) 1999 Computer Systems and Communication Lab,
 #   *                    Institute of Information Science, Academia
 #       *                    Sinica. All rights reserved.
 #   *
 #   * Redistribution and use in source and binary forms, with or without
 #   * modification, are permitted provided that the following conditions
 #   * are met:
 #   *
 #   * . Redistributions of source code must retain the above copyright
 #   *   notice, this list of conditions and the following disclaimer.
 #   * . Redistributions in binary form must reproduce the above copyright
 #   *   notice, this list of conditions and the following disclaimer in
 #   *   the documentation and/or other materials provided with the
 #   *   distribution.
 #   * . Neither the name of the Computer Systems and Communication Lab
 #   *   nor the names of its contributors may be used to endorse or
 #   *   promote products derived from this software without specific
 #   *   prior written permission.
 #   *
 #   * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 #   * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 #   * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 #   * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 #   * REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 #   * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 #   * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 #   * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 #   * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 #   * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 #   * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 #   * OF THE POSSIBILITY OF SUCH DAMAGE.
 #   */
 #
 #  Copyright 1996 Chih-Hao Tsai @ Beckman Institute,
 #      University of Illinois
 #  c-tsai4@uiuc.edu  http://casper.beckman.uiuc.edu/~c-tsai4
 #
 #  ---------------COPYING.libtabe-----END--------------------------------
 #
 #
 #  ---------------COPYING.ipadic-----BEGIN-------------------------------
 #
 #  Copyright 2000, 2001, 2002, 2003 Nara Institute of Science
 #  and Technology.  All Rights Reserved.
 #
 #  Use, reproduction, and distribution of this software is permitted.
 #  Any copy of this software, whether in its original form or modified,
 #  must include both the above copyright notice and the following
 #  paragraphs.
 #
 #  Nara Institute of Science and Technology (NAIST),
 #  the copyright holders, disclaims all warranties with regard to this
 #  software, including all implied warranties of merchantability and
 #  fitness, in no event shall NAIST be liable for
 #  any special, indirect or consequential damages or any damages
 #  whatsoever resulting from loss of use, data or profits, whether in an
 #  action of contract, negligence or other tortuous action, arising out
 #  of or in connection with the use or performance of this software.
 #
 #  A large portion of the dictionary entries
 #  originate from ICOT Free Software.  The following conditions for ICOT
 #  Free Software applies to the current dictionary as well.
 #
 #  Each User may also freely distribute the Program, whether in its
 #  original form or modified, to any third party or parties, PROVIDED
 #  that the provisions of Section 3 ("NO WARRANTY") will ALWAYS appear
 #  on, or be attached to, the Program, which is distributed substantially
 #  in the same form as set out herein and that such intended
 #  distribution, if actually made, will neither violate or otherwise
 #  contravene any of the laws and regulations of the countries having
 #  jurisdiction over the User or the intended distribution itself.
 #
 #  NO WARRANTY
 #
 #  The program was produced on an experimental basis in the course of the
 #  research and development conducted during the project and is provided
 #  to users as so produced on an experimental basis.  Accordingly, the
 #  program is provided without any warranty whatsoever, whether express,
 #  implied, statutory or otherwise.  The term "warranty" used herein
 #  includes, but is not limited to, any warranty of the quality,
 #  performance, merchantability and fitness for a particular purpose of
 #  the program and the nonexistence of any infringement or violation of
 #  any right of any third party.
 #
 #  Each user of the program will agree and understand, and be deemed to
 #  have agreed and understood, that there is no warranty whatsoever for
 #  the program and, accordingly, the entire risk arising from or
 #  otherwise connected with the program is assumed by the user.
 #
 #  Therefore, neither ICOT, the copyright holder, or any other
 #  organization that participated in or was otherwise related to the
 #  development of the program and their respective officials, directors,
 #  officers and other employees shall be held liable for any and all
 #  damages, including, without limitation, general, special, incidental
 #  and consequential damages, arising out of or otherwise in connection
 #  with the use or inability to use the program or any product, material
 #  or result produced or otherwise obtained by using the program,
 #  regardless of whether they have been advised of, or otherwise had
 #  knowledge of, the possibility of such damages at any time during the
 #  project or thereafter.  Each user will be deemed to have agreed to the
 #  foregoing by his or her commencement of use of the program.  The term
 #  "use" as used herein includes, but is not limited to, the use,
 #  modification, copying and distribution of the program and the
 #  production of secondary products from the program.
 #
 #  In the case where the program, whether in its original form or
 #  modified, was distributed or delivered to or received by a user from
 #  any person, organization or entity other than ICOT, unless it makes or
 #  grants independently of ICOT any specific warranty to the user in
 #  writing, such person, organization or entity, will also be exempted
 #  from and not be held liable to the user for any such damages as noted
 #  above as far as the program is concerned.
 #
 #  ---------------COPYING.ipadic-----END----------------------------------

----------------------------------------------------------------------

Lao Word Break Dictionary Data (laodict.txt)

 # Copyright (C) 2016 and later: Unicode, Inc. and others.
 # License & terms of use: http://www.unicode.org/copyright.html
 # Copyright (c) 2015 International Business Machines Corporation
 # and others. All Rights Reserved.
 #
 # Project: https://github.com/rober42539/lao-dictionary
 # Dictionary: https://github.com/rober42539/lao-dictionary/laodict.txt
 # License: https://github.com/rober42539/lao-dictionary/LICENSE.txt
 #          (copied below)
 #
 #	This file is derived from the above dictionary version of Nov 22, 2020
 #  ----------------------------------------------------------------------
 #  Copyright (C) 2013 Brian Eugene Wilson, Robert Martin Campbell.
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 #  modification, are permitted provided that the following conditions are met:
 #
 #  Redistributions of source code must retain the above copyright notice, this
 #  list of conditions and the following disclaimer. Redistributions in binary
 #  form must reproduce the above copyright notice, this list of conditions and
 #  the following disclaimer in the documentation and/or other materials
 #  provided with the distribution.
 #
 # THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 # "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 # LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 # FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 # COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 # INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 # (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 # SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 # HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 # STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 # ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 # OF THE POSSIBILITY OF SUCH DAMAGE.
 #  --------------------------------------------------------------------------

----------------------------------------------------------------------

Burmese Word Break Dictionary Data (burmesedict.txt)

 #  Copyright (c) 2014 International Business Machines Corporation
 #  and others. All Rights Reserved.
 #
 #  This list is part of a project hosted at:
 #    github.com/kanyawtech/myanmar-karen-word-lists
 #
 #  --------------------------------------------------------------------------
 #  Copyright (c) 2013, LeRoy Benjamin Sharon
 #  All rights reserved.
 #
 #  Redistribution and use in source and binary forms, with or without
 #  modification, are permitted provided that the following conditions
 #  are met: Redistributions of source code must retain the above
 #  copyright notice, this list of conditions and the following
 #  disclaimer.  Redistributions in binary form must reproduce the
 #  above copyright notice, this list of conditions and the following
 #  disclaimer in the documentation and/or other materials provided
 #  with the distribution.
 #
 #    Neither the name Myanmar Karen Word Lists, nor the names of its
 #    contributors may be used to endorse or promote products derived
 #    from this software without specific prior written permission.
 #
 #  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 #  CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 #  INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 #  MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 #  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
 #  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 #  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 #  TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 #  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 #  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 #  TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 #  THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 #  SUCH DAMAGE.
 #  --------------------------------------------------------------------------

----------------------------------------------------------------------

Time Zone Database

  ICU uses the public domain data and code derived from Time Zone
Database for its time zone support. The ownership of the TZ database
is explained in BCP 175: Procedure for Maintaining the Time Zone
Database section 7.

 # 7.  Database Ownership
 #
 #    The TZ database itself is not an IETF Contribution or an IETF
 #    document.  Rather it is a pre-existing and regularly updated work
 #    that is in the public domain, and is intended to remain in the
 #    public domain.  Therefore, BCPs 78 [RFC5378] and 79 [RFC3979] do
 #    not apply to the TZ Database or contributions that individuals make
 #    to it.  Should any claims be made and substantiated against the TZ
 #    Database, the organization that is providing the IANA
 #    Considerations defined in this RFC, under the memorandum of
 #    understanding with the IETF, currently ICANN, may act in accordance
 #    with all competent court orders.  No ownership claims will be made
 #    by ICANN or the IETF Trust on the database or the code.  Any person
 #    making a contribution to the database or code waives all rights to
 #    future claims in that contribution or in the TZ Database.

----------------------------------------------------------------------

Google double-conversion

Copyright 2006-2011, the V8 project authors. All rights reserved.
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of Google Inc. nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

----------------------------------------------------------------------

JSON parsing library (nlohmann/json)

File: vendor/json/upstream/single_include/nlohmann/json.hpp (only for ICU4C)

MIT License

Copyright (c) 2013-2022 Niels Lohmann

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

----------------------------------------------------------------------

File: aclocal.m4 (only for ICU4C)
Section: pkg.m4 - Macros to locate and utilise pkg-config.


Copyright © 2004 Scott James Remnant <scott@netsplit.com>.
Copyright © 2012-2015 Dan Nicholson <dbn.lists@gmail.com>

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
02111-1307, USA.

As a special exception to the GNU General Public License, if you
distribute this file as part of a program that contains a
configuration script generated by Autoconf, you may include it under
the same distribution terms that you use for the rest of that
program.


(The condition for the exception is fulfilled because
ICU4C includes a configuration script generated by Autoconf,
namely the `configure` script.)

----------------------------------------------------------------------

File: config.guess (only for ICU4C)


This file is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, see <https://www.gnu.org/licenses/>.

As a special exception to the GNU General Public License, if you
distribute this file as part of a program that contains a
configuration script generated by Autoconf, you may include it under
the same distribution terms that you use for the rest of that
program.  This Exception is an additional permission under section 7
of the GNU General Public License, version 3 ("GPLv3").


(The condition for the exception is fulfilled because
ICU4C includes a configuration script generated by Autoconf,
namely the `configure` script.)

----------------------------------------------------------------------

File: install-sh (only for ICU4C)


Copyright 1991 by the Massachusetts Institute of Technology

Permission to use, copy, modify, distribute, and sell this software and its
documentation for any purpose is hereby granted without fee, provided that
the above copyright notice appear in all copies and that both that
copyright notice and this permission notice appear in supporting
documentation, and that the name of M.I.T. not be used in advertising or
publicity pertaining to distribution of the software without specific,
written prior permission.  M.I.T. makes no representations about the
suitability of this software for any purpose.  It is provided "as is"
without express or implied warranty.

----------------------------------------------------------------------

File: sorttable.js (only for ICU4J)

The MIT Licence, for code from kryogenix.org

Code downloaded from the Browser Experiments section of kryogenix.org is
licenced under the so-called MIT licence. The licence is below.

Copyright (c) 1997-date Stuart Langridge

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
~~~~

### A.5 libpng (PNG Reference Library License version 2)

~~~~text
COPYRIGHT NOTICE, DISCLAIMER, and LICENSE
=========================================

PNG Reference Library License version 2
---------------------------------------

 * Copyright (c) 1995-2026 The PNG Reference Library Authors.
 * Copyright (c) 2018-2026 Cosmin Truta.
 * Copyright (c) 2000-2002, 2004, 2006-2018 Glenn Randers-Pehrson.
 * Copyright (c) 1996-1997 Andreas Dilger.
 * Copyright (c) 1995-1996 Guy Eric Schalnat, Group 42, Inc.

The software is supplied "as is", without warranty of any kind,
express or implied, including, without limitation, the warranties
of merchantability, fitness for a particular purpose, title, and
non-infringement.  In no event shall the Copyright owners, or
anyone distributing the software, be liable for any damages or
other liability, whether in contract, tort or otherwise, arising
from, out of, or in connection with the software, or the use or
other dealings in the software, even if advised of the possibility
of such damage.

Permission is hereby granted to use, copy, modify, and distribute
this software, or portions hereof, for any purpose, without fee,
subject to the following restrictions:

 1. The origin of this software must not be misrepresented; you
    must not claim that you wrote the original software.  If you
    use this software in a product, an acknowledgment in the product
    documentation would be appreciated, but is not required.

 2. Altered source versions must be plainly marked as such, and must
    not be misrepresented as being the original software.

 3. This Copyright notice may not be removed or altered from any
    source or altered source distribution.


PNG Reference Library License version 1 (for libpng 0.5 through 1.6.35)
-----------------------------------------------------------------------

libpng versions 1.0.7, July 1, 2000, through 1.6.35, July 15, 2018 are
Copyright (c) 2000-2002, 2004, 2006-2018 Glenn Randers-Pehrson, are
derived from libpng-1.0.6, and are distributed according to the same
disclaimer and license as libpng-1.0.6 with the following individuals
added to the list of Contributing Authors:

    Simon-Pierre Cadieux
    Eric S. Raymond
    Mans Rullgard
    Cosmin Truta
    Gilles Vollant
    James Yu
    Mandar Sahastrabuddhe
    Google Inc.
    Vadim Barkov

and with the following additions to the disclaimer:

    There is no warranty against interference with your enjoyment of
    the library or against infringement.  There is no warranty that our
    efforts or the library will fulfill any of your particular purposes
    or needs.  This library is provided with all faults, and the entire
    risk of satisfactory quality, performance, accuracy, and effort is
    with the user.

Some files in the "contrib" directory and some configure-generated
files that are distributed with libpng have other copyright owners, and
are released under other open source licenses.

libpng versions 0.97, January 1998, through 1.0.6, March 20, 2000, are
Copyright (c) 1998-2000 Glenn Randers-Pehrson, are derived from
libpng-0.96, and are distributed according to the same disclaimer and
license as libpng-0.96, with the following individuals added to the
list of Contributing Authors:

    Tom Lane
    Glenn Randers-Pehrson
    Willem van Schaik

libpng versions 0.89, June 1996, through 0.96, May 1997, are
Copyright (c) 1996-1997 Andreas Dilger, are derived from libpng-0.88,
and are distributed according to the same disclaimer and license as
libpng-0.88, with the following individuals added to the list of
Contributing Authors:

    John Bowler
    Kevin Bracey
    Sam Bushell
    Magnus Holmgren
    Greg Roelofs
    Tom Tanner

Some files in the "scripts" directory have other copyright owners,
but are released under this license.

libpng versions 0.5, May 1995, through 0.88, January 1996, are
Copyright (c) 1995-1996 Guy Eric Schalnat, Group 42, Inc.

For the purposes of this copyright and license, "Contributing Authors"
is defined as the following set of individuals:

    Andreas Dilger
    Dave Martindale
    Guy Eric Schalnat
    Paul Schmidt
    Tim Wegner

The PNG Reference Library is supplied "AS IS".  The Contributing
Authors and Group 42, Inc. disclaim all warranties, expressed or
implied, including, without limitation, the warranties of
merchantability and of fitness for any purpose.  The Contributing
Authors and Group 42, Inc. assume no liability for direct, indirect,
incidental, special, exemplary, or consequential damages, which may
result from the use of the PNG Reference Library, even if advised of
the possibility of such damage.

Permission is hereby granted to use, copy, modify, and distribute this
source code, or portions hereof, for any purpose, without fee, subject
to the following restrictions:

 1. The origin of this source code must not be misrepresented.

 2. Altered versions must be plainly marked as such and must not
    be misrepresented as being the original source.

 3. This Copyright notice may not be removed or altered from any
    source or altered source distribution.

The Contributing Authors and Group 42, Inc. specifically permit,
without fee, and encourage the use of this source code as a component
to supporting the PNG file format in commercial products.  If you use
this source code in a product, acknowledgment is not required but would
be appreciated.
~~~~

### A.6 libjpeg-turbo (IJG, Modified BSD and zlib licences)

This is the licence summary file of libjpeg-turbo, followed by the "LEGAL ISSUES" section of its `README.ijg`
(the IJG licence itself).

~~~~text
libjpeg-turbo Licenses
======================

libjpeg-turbo is covered by two compatible BSD-style open source licenses:

- The IJG (Independent JPEG Group) License, which is listed in
  [README.ijg](README.ijg)

  This license applies to the libjpeg API library and associated programs,
  including any code inherited from libjpeg and any modifications to that
  code.  Note that the libjpeg-turbo SIMD source code bears the
  [zlib License](https://opensource.org/licenses/Zlib), but in the context of
  the overall libjpeg API library, the terms of the zlib License are subsumed
  by the terms of the IJG License.

- The Modified (3-clause) BSD License, which is listed below

  This license applies to the TurboJPEG API library and associated programs, as
  well as the build system.  Note that the TurboJPEG API library wraps the
  libjpeg API library, so in the context of the overall TurboJPEG API library,
  both the terms of the IJG License and the terms of the Modified (3-clause)
  BSD License apply.


Complying with the libjpeg-turbo Licenses
=========================================

This section provides a roll-up of the libjpeg-turbo licensing terms, to the
best of our understanding.  This is not a license in and of itself.  It is
intended solely for clarification.

1.  If you are distributing a modified version of the libjpeg-turbo source,
    then:

    1.  You cannot alter or remove any existing copyright or license notices
        from the source.

        **Origin**
        - Clause 1 of the IJG License
        - Clause 1 of the Modified BSD License
        - Clauses 1 and 3 of the zlib License

    2.  You must add your own copyright notice to the header of each source
        file you modified, so others can tell that you modified that file.  (If
        there is not an existing copyright header in that file, then you can
        simply add a notice stating that you modified the file.)

        **Origin**
        - Clause 1 of the IJG License
        - Clause 2 of the zlib License

    3.  You must include the IJG README file, and you must not alter any of the
        copyright or license text in that file.

        **Origin**
        - Clause 1 of the IJG License

2.  If you are distributing only libjpeg-turbo binaries without the source, or
    if you are distributing an application that statically links with
    libjpeg-turbo, then:

    1.  Your product documentation must include a message stating:

        This software is based in part on the work of the Independent JPEG
        Group.

        **Origin**
        - Clause 2 of the IJG license

    2.  If your binary distribution includes or uses the TurboJPEG API, then
        your product documentation must include the text of the Modified BSD
        License (see below.)

        **Origin**
        - Clause 2 of the Modified BSD License

3.  You cannot use the name of the IJG or The libjpeg-turbo Project or the
    contributors thereof in advertising, publicity, etc.

    **Origin**
    - IJG License
    - Clause 3 of the Modified BSD License

4.  The IJG and The libjpeg-turbo Project do not warrant libjpeg-turbo to be
    free of defects, nor do we accept any liability for undesirable
    consequences resulting from your use of the software.

    **Origin**
    - IJG License
    - Modified BSD License
    - zlib License


The Modified (3-clause) BSD License
===================================

Copyright (C)2009-2024 D. R. Commander.  All Rights Reserved.<br>
Copyright (C)2015 Viktor Szathmáry.  All Rights Reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice,
  this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.
- Neither the name of the libjpeg-turbo Project nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS",
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.


Why Two Licenses?
=================

The zlib License could have been used instead of the Modified (3-clause) BSD
License, and since the IJG License effectively subsumes the distribution
conditions of the zlib License, this would have effectively placed
libjpeg-turbo binary distributions under the IJG License.  However, the IJG
License specifically refers to the Independent JPEG Group and does not extend
attribution and endorsement protections to other entities.  Thus, it was
desirable to choose a license that granted us the same protections for new code
that were granted to the IJG for code derived from their software.
~~~~

~~~~text
LEGAL ISSUES
============

In plain English:

1. We don't promise that this software works.  (But if you find any bugs,
   please let us know!)
2. You can use this software for whatever you want.  You don't have to pay us.
3. You may not pretend that you wrote this software.  If you use it in a
   program, you must acknowledge somewhere in your documentation that
   you've used the IJG code.

In legalese:

The authors make NO WARRANTY or representation, either express or implied,
with respect to this software, its quality, accuracy, merchantability, or
fitness for a particular purpose.  This software is provided "AS IS", and you,
its user, assume the entire risk as to its quality and accuracy.

This software is copyright (C) 1991-2020, Thomas G. Lane, Guido Vollbeding.
All Rights Reserved except as specified below.

Permission is hereby granted to use, copy, modify, and distribute this
software (or portions thereof) for any purpose, without fee, subject to these
conditions:
(1) If any part of the source code for this software is distributed, then this
README file must be included, with this copyright and no-warranty notice
unaltered; and any additions, deletions, or changes to the original files
must be clearly indicated in accompanying documentation.
(2) If only executable code is distributed, then the accompanying
documentation must state that "this software is based in part on the work of
the Independent JPEG Group".
(3) Permission for use of this software is granted only if the user accepts
full responsibility for any undesirable consequences; the authors accept
NO LIABILITY for damages of any kind.

These conditions apply to any software derived from or based on the IJG code,
not just to the unmodified library.  If you use our work, you ought to
acknowledge us.

Permission is NOT granted for the use of any IJG author's name or company name
in advertising or publicity relating to this software or products derived from
it.  This software may be referred to only as "the Independent JPEG Group's
software".

We specifically permit and encourage the use of this software as the basis of
commercial products, provided that all warranty or liability claims are
assumed by the product vendor.
~~~~

### A.7 libwebp (BSD-3-Clause)

~~~~text
Copyright (c) 2010, Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in
    the documentation and/or other materials provided with the
    distribution.

  * Neither the name of Google nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
~~~~

### A.8 zlib

~~~~text
version 1.2.12, March 27th, 2022

Copyright (C) 1995-2022 Jean-loup Gailly and Mark Adler

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
~~~~

### A.9 Expat (MIT)

~~~~text
Copyright (c) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper
Copyright (c) 2001-2025 Expat maintainers

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
~~~~

### A.10 skcms (BSD-3-Clause)

~~~~text
// Copyright (c) 2018 Google Inc. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

--------------------------------------------------------------------------------
~~~~

### A.11 whisper.cpp and ggml (MIT)

~~~~text
MIT License

Copyright (c) 2023-2026 The ggml authors

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
~~~~

### A.12 RNNoise (BSD-3-Clause)

~~~~text
Copyright (c) 2017, Mozilla
Copyright (c) 2007-2017, Jean-Marc Valin
Copyright (c) 2005-2017, Xiph.Org Foundation
Copyright (c) 2003-2004, Mark Borgerding

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

- Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

- Neither the name of the Xiph.Org Foundation nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION
OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
~~~~

### A.13 SIL Open Font License 1.1 (Google Fonts built into the app)

~~~~text
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.

### A.14 LAME (GNU Library General Public License, version 2)

~~~~text
                  GNU LIBRARY GENERAL PUBLIC LICENSE
                       Version 2, June 1991

 Copyright (C) 1991 Free Software Foundation, Inc.
                        59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the library GPL.  It is
 numbered 2 because it goes with version 2 of the ordinary GPL.]

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Library General Public License, applies to some
specially designated Free Software Foundation software, and to any
other libraries whose authors decide to use it.  You can use it for
your libraries, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
this service if you wish), that you receive source code or can get it
if you want it, that you can change the software or use pieces of it
in new free programs; and that you know you can do these things.

  To protect your rights, we need to make restrictions that forbid
anyone to deny you these rights or to ask you to surrender the rights.
These restrictions translate to certain responsibilities for you if
you distribute copies of the library, or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link a program with the library, you must provide
complete object files to the recipients so that they can relink them
with the library, after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  Our method of protecting your rights has two steps: (1) copyright
the library, and (2) offer you this license which gives you legal
permission to copy, distribute and/or modify the library.

  Also, for each distributor's protection, we want to make certain
that everyone understands that there is no warranty for this free
library.  If the library is modified by someone else and passed on, we
want its recipients to know that what they have is not the original
version, so that any problems introduced by others will not reflect on
the original authors' reputations.

  Finally, any free program is threatened constantly by software
patents.  We wish to avoid the danger that companies distributing free
software will individually obtain patent licenses, thus in effect
transforming the program into proprietary software.  To prevent this,
we have made it clear that any patent must be licensed for everyone's
free use or not licensed at all.

  Most GNU software, including some libraries, is covered by the ordinary
GNU General Public License, which was designed for utility programs.  This
license, the GNU Library General Public License, applies to certain
designated libraries.  This license is quite different from the ordinary
one; be sure to read it in full, and don't assume that anything in it is
the same as in the ordinary license.

  The reason we have a separate public license for some libraries is that
they blur the distinction we usually make between modifying or adding to a
program and simply using it.  Linking a program with a library, without
changing the library, is in some sense simply using the library, and is
analogous to running a utility program or application program.  However, in
a textual and legal sense, the linked executable is a combined work, a
derivative of the original library, and the ordinary General Public License
treats it as such.

  Because of this blurred distinction, using the ordinary General
Public License for libraries did not effectively promote software
sharing, because most developers did not use the libraries.  We
concluded that weaker conditions might promote sharing better.

  However, unrestricted linking of non-free programs would deprive the
users of those programs of all benefit from the free status of the
libraries themselves.  This Library General Public License is intended to
permit developers of non-free programs to use free libraries, while
preserving your freedom as a user of such programs to change the free
libraries that are incorporated in them.  (We have not seen how to achieve
this as regards changes in header files, but we have achieved it as regards
changes in the actual functions of the Library.)  The hope is that this
will lead to faster development of free libraries.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, while the latter only
works together with the library.

  Note that it is possible for a library to be covered by the ordinary
General Public License rather than by this special one.

                  GNU LIBRARY GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library which
contains a notice placed by the copyright holder or other authorized
party saying it may be distributed under the terms of this Library
General Public License (also called "this License").  Each licensee is
addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.
  
  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.

  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.

  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.

  6. As an exception to the Sections above, you may also compile or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    c) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    d) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the source code distributed need not include anything that is normally
distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.

  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties to
this License.

  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Library General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.

  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

                            NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

                     END OF TERMS AND CONDITIONS

           How to Apply These Terms to Your New Libraries

  If you develop a new library, and you want it to be of the greatest
possible use to the public, we recommend making it free software that
everyone can redistribute and change.  You can do so by permitting
redistribution under these terms (or, alternatively, under the terms of the
ordinary General Public License).

  To apply these terms, attach the following notices to the library.  It is
safest to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least the
"copyright" line and a pointer to where the full notice is found.

    <one line to give the library's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Library General Public
    License as published by the Free Software Foundation; either
    version 2 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Library General Public License for more details.

    You should have received a copy of the GNU Library General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the library, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the
  library `Frob' (a library for tweaking knobs) written by James Random Hacker.

  <signature of Ty Coon>, 1 April 1990
  Ty Coon, President of Vice

That's all there is to it!
~~~~
