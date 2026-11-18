# DepthFlix — S25 Ultra

**3D video and photo player for the Galaxy S25 Ultra with a SpatialGlass 3D film**
**갤럭시 S25 Ultra + SpatialGlass 3D 필름용 3D 영상·사진 플레이어**

*[한국어](#한국어) · [English](#english)*

---

## 한국어

Leia 태블릿용 [DepthFlix](https://github.com/nautymac/DepthFlix)의 **갤럭시 S25 Ultra + SpatialGlass 3D 필름** 버전입니다. SBS·TB 3D 영상과 사진, 그리고 일반 2D 영상을 폰에서 안경 없이 3D로 봅니다.

이 필름은 **렌즈일 뿐**이라 폰에 3D 엔진이 없습니다. 그래서 앱이 직접 합니다.

- **위빙**: 두 눈 그림을 화소마다 필름 렌즈에 맞춰 섞습니다. 색(빨강·초록·파랑)마다 따로 측정한 섞기 곡선을 씁니다.
- **눈 추적**: 앞카메라로 두 눈의 홍채를 초당 약 60번 찾아, 머리를 움직여도 3D가 유지되게 위빙을 옮깁니다. 기기 안에서만 처리합니다.

### 기능

| 기능 | 설명 |
|---|---|
| **3D 영상·사진** | SBS/TB(half·full)를 화소로 판별해 3D로 보여 줍니다. 2D 영상은 시차를 만들어 3D로 봅니다. |
| **3D 조절 바** | 재생 중 오른쪽 위 **3D 조절** → 위상·추적 세기·밝기·대비를 보면서 맞춥니다. 값은 저장됩니다. |
| **밝기·대비 보정** | 필름 3D는 각 눈에 화소 일부만 가서 어둡습니다. 기본으로 밝기 1.4, 대비 1.15로 올립니다(조절 가능). |
| **정중앙 배치** | 카메라 구멍과 상관없이 그림을 화면 정중앙에 같은 여백으로 놓습니다. |
| **USB 드라이브** | 목록 위 **USB** 버튼. 처음 한 번 드라이브를 허락하면 폴더·영상·사진을 바로 둘러보고 3D로 엽니다. 버튼을 길게 누르면 다른 드라이브를 고릅니다. |
| **자막** | 영상 옆의 .srt/.smi 등을 자동으로 붙입니다(USB 포함, 한국어 우선). 없으면 영상 안의 자막을 자동으로 켭니다. |
| **3D 맞추기 도구** | 런처의 **DepthFlix 3D 맞추기**: 필름 렌즈 값을 맞추고(가로·세로·이미지 3단계) SBS 사진을 3D로 봅니다. 플레이어와 같은 값을 씁니다. |
| **그 밖에** | SMB·DLNA·URL 재생, DTS·AC3 소리 재생(FFmpeg), 이어 보기 등 DepthFlix 기능 그대로. |

### 설치

1. [Releases](../../releases)에서 `DepthFlix-S25Ultra-*.apk`를 받아 설치합니다.
2. 처음 실행할 때 **저장소** 권한을, 3D를 처음 볼 때 **카메라** 권한(눈 추적용)을 허용합니다.

패키지가 `com.nauty.p3d.glass`라 다른 DepthFlix와 함께 설치할 수 있습니다.

### 잘 보는 법

- **거리 30~40 cm**에서 보세요. 20 cm처럼 너무 가까우면 2D가 됩니다.
- **앞카메라가 왼쪽**에 오게 가로로 듭니다.
- 겹치거나 뒤집혀 보이면 **3D 조절 › 위상**을 천천히 움직여 가장 선명한 곳에 둡니다. 머리를 옆으로 옮길 때 깨지면 **추적**(1.5~2.5)을 조절합니다.
- 처음 쓰는 폰·필름이면 **DepthFlix 3D 맞추기 › 보정**에서 가로·세로 줄무늬를 먼저 맞추세요.

### 빌드

`glassGithub` 플레이버입니다(`app3d/app/src/glass/`).

```
cd app3d
gradlew assembleGlassGithubDebug
```

github 배포판은 Leia 백엔드도 함께 컴파일하므로 `app3d/app/libs/redmagic/leia-cnsdk*.jar`가 **컴파일에만** 필요합니다(APK에는 들어가지 않고 S25에서는 쓰이지 않습니다). 필름 렌즈 값(간격 1.51207, 기울기 −0.19057)은 S25 Ultra(SM-S938N)에서 측정한 값입니다.

Leia 태블릿용 원래 설명: [docs/README.leia.ko.md](docs/README.leia.ko.md)

---

## English

The **Galaxy S25 Ultra + SpatialGlass 3D film** edition of [DepthFlix](https://github.com/nautymac/DepthFlix) (originally for Leia tablets). Watch SBS/TB 3D videos and photos, and ordinary 2D videos, in 3D on the phone without glasses.

The film is **only a lens**: the phone has no 3D engine. So the app does it itself:

- **Weaving**: both eyes' pictures are mixed per pixel to match the film's lenses, with blending curves measured separately for red, green and blue.
- **Eye tracking**: the front camera finds both irises about 60 times a second and moves the weave with your head, so 3D holds as you move. Processed on the phone only.

### Features

| Feature | What it does |
|---|---|
| **3D videos and photos** | Detects SBS/TB (half and full) from the pixels and shows them in 3D; 2D videos get synthetic parallax. |
| **3D tune bar** | While playing, tap **3D tune** (top right) to adjust phase, tracking strength, brightness and contrast while watching. Saved. |
| **Brightness and contrast lift** | Film 3D is dimmer because each eye gets only part of the pixels. Lifted by default (brightness 1.4, contrast 1.15), adjustable. |
| **Dead-centre placement** | The picture sits in the exact centre of the panel with equal bars, whatever the camera cutout does to the window. |
| **USB drives** | The **USB** button above the list. Allow the drive once, then browse its folders, videos and photos and open them in 3D. Long-press to pick another drive. |
| **Subtitles** | Picks up .srt/.smi files next to the video automatically (USB included, Korean first); otherwise turns on the video's built-in subtitles. |
| **3D Fit tool** | **DepthFlix 3D Fit** in the launcher: calibrates the film's lens values (horizontal, vertical, image) and views SBS photos in 3D; the player uses the same values. |
| **And the rest** | SMB, DLNA and URL playback, DTS/AC3 audio (FFmpeg), resume, and the other DepthFlix features. |

### Install

1. Download `DepthFlix-S25Ultra-*.apk` from [Releases](../../releases) and install it.
2. Allow **storage** on first launch and the **camera** (eye tracking) the first time you watch in 3D.

Its package is `com.nauty.p3d.glass`, so it installs next to other DepthFlix builds.

### Getting a good picture

- Watch from **30–40 cm**. Much closer (about 20 cm) it turns 2D.
- Hold the phone in landscape with the **front camera on the left**.
- If the picture looks doubled or inside-out, slide **3D tune › Phase** slowly to the sharpest point. If it breaks as you move your head sideways, adjust **Track** (1.5–2.5).
- On a new phone or film, first line up the stripes in **DepthFlix 3D Fit › Calibrate**.

### Build

It is the `glassGithub` flavour (`app3d/app/src/glass/`).

```
cd app3d
gradlew assembleGlassGithubDebug
```

The github distribution also compiles the Leia backend, so `app3d/app/libs/redmagic/leia-cnsdk*.jar` is needed **at compile time only** (it is not packaged and never used on the S25). The film's lens values (pitch 1.51207, slant −0.19057) were measured on an S25 Ultra (SM-S938N).

Original Leia tablet documentation: [docs/README.leia.en.md](docs/README.leia.en.md)

---

Not affiliated with Samsung, the film's maker or Leia.
