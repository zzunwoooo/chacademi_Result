# Launcher Korean UI and Chacademi branding

Baseline: `26342dfae8d96204476e853e2c8e1b83d18a9e16`.

- Korean locale covers all 266 original language keys and preserves placeholders. Functional, account, security and legal notices remain. Known local validation failures are translated at the UI boundary; original checks and diagnostic logs are unchanged. Unrecognized operating-system diagnostics remain available as escaped technical details under a Korean label.
- The unused Westeros welcome view is no longer rendered. The original loading text image is replaced by a language-neutral spinner. Upstream attribution remains in the About panel and license files.
- Fonts are the existing Chacademi client mapping: `body.json` → Pretendard Regular, `label.json` → Pretendard Medium, `title.json`/`heading.json` → MaruBuri SemiBold. Copied font licenses are retained.
- User-saved generated logo SHA256: `5dd93d34c916626bdb377bd1fd5070fa542c730613a321c17cc70acf87fff515`. The 1024×1024 RGBA master is `build/icon.png`. The ICO contains 16, 24, 32, 48, 64, 128 and 256 px PNG images; ICNS includes 32 through 1024 px representations. Window, installer, uninstaller, executable, Linux and macOS icon paths are explicit.
- Backgrounds, distribution.json, manifests and the existing automatic-launch policy are not changed.

## Validation

`node --test tools/localization.test.js tools/autostart.test.js tools/launchlifecycle.test.js tools/launchpolicy.test.js tools/clientassets.test.js tools/settingspolicy.test.js tools/shaderdefaults.test.js`

All seven files passed, including eight shader scenarios. The shader fixture is an existing inspected Complementary ZIP, verified against policy SHA256 `61d133b24b6a92e46a3d7df791bb2ff163bbba10eed59df979b9bd727c24fbaf`; it is ignored and not committed. The lifecycle VM uses the production error translation module.

Focused ESLint and git diff whitespace checks passed. Isolated offscreen Electron rendering verified six screens and all three fonts without running launcher startup, authentication, the updater or a personal game instance. This is renderer QA, not end-to-end account/game validation. macOS/Linux packages were not built.

Windows build: `node node_modules/electron-builder/cli.js --win nsis --publish never`.
The host cannot create macOS symlinks from the official winCodeSign tool archive. The official downloaded archive was extracted into the normal versioned cache excluding its unused `darwin` subtree. Windows rcedit then embedded all seven icon images. No user privileges or security settings were changed. Both application and installer PE icon payloads were checked byte-for-byte against the ICO. The installer is unsigned; no signing credentials were configured.

## Coordinated release only

The built installer keeps baseline version 0.1.28 and is a review candidate, not a new release. Do not overwrite the published 0.1.28 asset. Integrate this commit with the deployment task, assign the next unused version (update package.json and package-lock.json together), rebuild with `--publish never`, recheck icons/installer and publish only through the coordinated release owner. Do not replace distribution.json or write the feed from this branch independently.

## Display-name follow-up (source only)

User-facing name is now **차카데미**: window/UI/About title, package product name, installer/product title, uninstall display name, shortcut name and macOS Dock label. The description no longer calls this a test launcher.

Internal package name `chacademi-launcher`, appId `kr.chacademi.launcher`, executable filename `Chacademi Launcher`, existing userData path, updater cache identity, artifact filename pattern, version and publishing configuration are preserved. The current builder computes the same Windows installation directory from the unchanged executableName.

This follow-up is source-only. **The existing 0.1.28 installer and screenshots predate this display-name change and must not be described as reflecting it.** No new build, launcher execution, server operation, push or publication was performed. Wait for final Claude source completion, integrate this follow-up with the branding commit, then perform the coordinated next-version build. Verify the visible name and unchanged upgrade/install path during that authorized build.
