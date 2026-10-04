# Third-party sources and assets

| Component                   | Source                                           | License                                                      |
|-----------------------------|--------------------------------------------------|--------------------------------------------------------------|
| AnZhiYu theme 1.7.0         | https://github.com/anzhiyu-c/hexo-theme-anzhiyu  | GPL-3.0                                                      |
| anzhiyu-theme-static 1.1.10 | npm package of the same name                     | ISC; bundled libraries retain their upstream notices         |
| Hexo                        | https://hexo.io/                                 | MIT                                                          |
| Vue                         | https://vuejs.org/                               | MIT                                                          |
| Phosphor icons              | https://github.com/phosphor-icons/vue            | MIT                                                          |
| QRCode.js                   | https://github.com/davidshimjs/qrcodejs          | MIT                                                          |
| medium-zoom 1.1.0           | https://github.com/francoischalifour/medium-zoom | MIT                                                          |
| node-snackbar 0.1.16        | https://github.com/polonel/SnackBar              | MIT                                                          |
| anzhiyu-blog-static 1.0.1   | npm package; theme's APlayer bundle              | ISC package; embedded library notices apply                  |
| hexo-anzhiyu-music 1.0.1    | npm package; theme's Meting bundle               | Apache-2.0 package; embedded library notices apply           |
| Pjax 0.2.8                  | https://github.com/MoOx/pjax                     | MIT; pinned to the theme's version                           |
| DOMPurify                   | https://github.com/cure53/DOMPurify              | Apache-2.0 OR MPL-2.0                                        |
| sanitize-html               | https://github.com/apostrophecms/sanitize-html   | MIT                                                          |
| React Bits Particles-JS-CSS | https://reactbits.dev/backgrounds/particles      | MIT + Commons Clause; full notice in licenses/react-bits.txt |
| OGL 1.0.11                  | https://github.com/oframe/ogl                    | Unlicense; full notice in licenses/ogl.txt                   |

The theme is installed from the official npm package and is not edited in
`node_modules`. Theme attribution remains in the blog footer. Integration
filters/configuration live in this repository. Consult the upstream licenses
when redistributing theme code; this file does not replace those licenses.

The particle background adapts the official React Bits shaders and behavior at
commit `1eeb6f105c68b964289d85dabbe84a1d551f3797` for this Hexo/Vue website.
It uses OGL directly, without introducing React or a shadcn application scaffold.
React Bits permits use within an application/website, but its Commons Clause
restricts selling or redistributing standalone components, including ports. This
site adaptation is not a separately distributed component library. Both full
notices are included in the generated site under `vendor/licenses/`.

The owner selected [ColdDay/click-colorful](https://github.com/ColdDay/click-colorful)
as a visual reference for colorful click bursts. At commit
`6e76b8b552a1d3d13b3e3111a35978110e181cd2`, the repository has no declared
license. Its JavaScript is not copied, bundled or loaded remotely. The site's
independent implementation lives in `source/js/site-click-effect.js`; it uses
CSS ball animations and lifecycle/interaction guards, with the reference's four
default colors, 30px size and source-code count of 30 (the README example says 50).
This reference does not grant a license to redistribute the upstream script.

Swiper is copied from `anzhiyu-theme-static/swiper/`; its bundled header states
MIT. The owner supplied the NetEase playlist ID `939817038`. Song metadata,
covers are optional public NetEase website metadata; audio is hosted in the
owner's authorized ImgBed/Telegram catalog. The Meting UI component remains,
but its community API is not called. These resources are not claimed as original.

`source/img/site-logo.jpg` restores the project's original favicon/brand image
from the pre-cleanup backup. `source/img/avatar.jpg` is the independently
replaceable owner-supplied avatar. These existing images retain their original
provenance and are not newly claimed as original work.

`source/img/javerry-sky.webp` is an original AI-generated landscape used as
the blog's fallback cover. It is
not copied from the theme author's illustrations. Generation brief: a bright
blue summer sky, sculptural white clouds, green mountains and distant lake,
small paper airplane, hand-painted editorial animation-background sensibility,
no people, text or logo. Converted to 1440×960 WebP for use as a local asset.
The retired portfolio's copies and responsive encodings were removed from
the project on 2026-10-03 after being archived outside the repository. See
`../docs/project-cleanup-2026-10-03.md` for the recovery location. The active
blog fallback cover remains in `source/img/javerry-sky.webp`.
