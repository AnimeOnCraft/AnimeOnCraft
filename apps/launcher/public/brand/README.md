# AnimeOnCraft — launcher artwork

2026-10-11. Built-in imagegen mode, user-supplied references: the neon ClanWars launcher (style reference only) and the crowned white cat icon (mascot identity). The references are not application data.

- `animeoncraft-avatar.png`: square Minecraft-style crowned white voxel cat. Used in launcher branding, decorative sidebar and favicon. Tauri CLI generates native sizes and `src-tauri/icons/icon.ico` from this file.
- `animeoncraft-cover.png`: wide Minecraft-style fantasy landscape with the same cat and the title **AnimeOnCraft**. Used as the home cover and blurred decorative backdrop. Contains no game version, player counts, release status, news or controls.

The original uploaded images and generated originals are preserved. Project assets are local, require no external image host and fit the existing CSP. UI text, services, errors, pagination, settings and disabled controls come from the existing application.

- `src/assets/hero-art.jpg` (0.7.38): правая часть `animeoncraft-cover.png` без надписи (кадр x 1180–2098, вся высота) — фон главной. Новой графики не генерировалось.

## Avatar prompt

Use case: style-transfer. Asset type: final square launcher avatar / Windows application icon, high resolution 1024x1024. Reference image 1 defines the mascot identity: a cute seated white cat with black eyes, tiny black nose, simple black facial markings, rounded plump body, small paws, striped tail to its left, little gold crown, purple sparkles. Recreate this SAME recognizable mascot as a polished Minecraft-inspired voxel / blocky 3D character: cubic head and ears, stepped blocky silhouette, white pixel-textured fur, black pixel eyes and markings, small gold voxel crown. Center the full seated cat, front view with very slight 3D depth, generous safe margins (entire cat and crown within central 75% so legible at 32px). Dark midnight purple square background, restrained magenta-violet rim light and a few pixel star sparkles. Match the friendly simplicity and white cat identity of reference; crisp high contrast silhouette, elegant game launcher branding. No text, no letters, no extra characters, no UI, no mockup, no border frame, no watermark. This is finished raster art to use as an application avatar.

### Crown correction

Edit only the gold head accessory of this square Minecraft-style cat launcher avatar. Replace the tall single gold spike/tower with a small recognizable royal gold CROWN: low horizontal gold band, exactly three short symmetrical square-tipped points (center slightly higher), visible resting across the top of the cat's head. Crown height no more than one fifth of the head height. It must read as a royal crown, not a horn, cone, tower or unicorn. Preserve all other parts exactly: same seated white voxel cat, face, body, paws, striped tail, Minecraft style, dark purple background, pink rim lighting, purple pixel star sparkles, composition and square proportions. No text, no extra accessories. Finished launcher application avatar.

## Cover prompt

Use case: stylized-concept. Asset type: finished ultra-wide launcher cover banner, landscape about 3:1, 1792x640 if possible. Reference image 1: exact mascot identity and Minecraft voxel rendering, white seated blocky cat, black pixel eyes, striped tail and little gold crown with THREE SHORT POINTS; use this same cat as the ONLY foreground character. Reference image 2: mood and art direction ONLY — cinematic block-built fantasy castle landscape, purple-magenta twilight, warm pink sunset at the far horizon, dark navy, fuchsia glow; do NOT copy the application interface, human character, ClanWars text, statistics or panels. Cover artwork only. Compose the recognizable crowned white voxel cat on the RIGHT third, seated on stone voxel blocks, facing viewer, majestic but cute, purple rim light and a few purple pixel sparkles. Behind it distant Minecraft-style castle towers and floating block islands with restrained violet glowing windows, atmospheric depth. In the LEFT half, UPPER half of the banner, place large very legible powerful brush/gaming title, exact text: "AnimeOnCraft" (A-n-i-m-e-O-n-C-r-a-f-t, exact capitalization and spelling), pale lavender-white with vivid magenta-violet glow, on a darker uncluttered background. Keep title inside generous safe margins. Leave bottom left third darker and clean to accommodate real launcher controls added by the app; do not draw those controls. Keep entire cat face, crown and entire title visible within safe central area for responsive crop. Text must be precisely "AnimeOnCraft" and NO OTHER TEXT. No slogans, no version number, no news, no players, no indicators, no buttons, no UI, no mockup border, no watermarks. Rich polished cinematic 3D voxel illustration harmonious with the attached icon.

## Regenerate native sizes

From repository root:

```powershell
corepack pnpm@12.10.1 --filter @animeoncraft/launcher exec tauri icon .\public\brand\animeoncraft-avatar.png --output .\src-tauri\icons
```

This changes image/icon resources only; it does not enable bundling, signing or updates.
