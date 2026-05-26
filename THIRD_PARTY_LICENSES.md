# Third-Party Licenses and Acknowledgments

This project incorporates code and resources from several third-party
open-source projects. Each is listed below with its author and license.
Full license texts are available at the linked project pages.

The application as a whole is distributed under the GNU General Public
License v3 (see the [LICENSE](LICENSE) file). Components below retain
their own copyright; their licenses are compatible with GPLv3.

---

## Upstream project

### GRBLController
- **Author:** zeevy
- **Source:** https://github.com/zeevy/grblcontroller (archived)
- **License:** GNU General Public License v3.0
- **Notes:** This project is a fork of GRBLController. The original
  copyright on the inherited files is preserved.

---

## Code derived from other projects

### Universal Gcode Sender (UGS)
- **Author:** Will Winder and contributors
- **Source:** https://github.com/winder/Universal-G-Code-Sender
- **License:** GNU General Public License v3.0
- **Used in:** `GcodeRenderer.java`, `GcodeVisualizerFragment.java`
  (the OpenGL G-code rendering pipeline is ported and adapted from UGS).

### ACE Editor
- **Author:** Ajax.org B.V., Mozilla and contributors
- **Source:** https://github.com/ajaxorg/ace
- **License:** BSD 3-Clause License
- **Used in:** `app/src/main/assets/js/ace.js`,
  `ext-searchbox.js`, `mode-gcode.js`, `theme-tomorrow_night.js`
  (embedded in the in-app G-code editor via WebView).

---

## Runtime dependencies

These libraries are pulled in via Gradle (see `app/build.gradle`):

| Library | Author | License |
|---|---|---|
| AndroidX (AppCompat, ConstraintLayout, RecyclerView, CardView, Preference, GridLayout, VectorDrawable) | Google / The Android Open Source Project | Apache License 2.0 |
| Material Components for Android | Google | Apache License 2.0 |
| Firebase Core / Messaging | Google | Apache License 2.0 |
| Apache Commons Collections 4 | The Apache Software Foundation | Apache License 2.0 |
| EventBus | Markus Junginger / greenrobot | Apache License 2.0 |
| IndicatorSeekBar | Chuang Guangquan (warkiz) | Apache License 2.0 |
| UsbSerial | Felipe Herranz (felHR85) | MIT License |
| MaterialFilePicker | nbsp-team | Apache License 2.0 |
| Sugar ORM | satyan | MIT License |
| Toasty | GrenderG | GNU Lesser General Public License v3.0 |
| android-iconify | Joan Zapata | Apache License 2.0 |
| JUnit (test only) | The JUnit Team | Eclipse Public License 1.0 |

---

## How to obtain the source

This application is free software released under the GPLv3. The full
corresponding source code is available at the project repository.
You may also request a copy by contacting the maintainer.
