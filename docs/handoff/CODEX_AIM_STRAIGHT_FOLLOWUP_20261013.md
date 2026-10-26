# Aim straight-plane follow-up — build only, not deployed

Base: 6a3c64853bc71385492a2802f2846ff7d0605bc4. Isolated hosting worktree/branch: C:/Chacademi/staging/aim-straight-20261013/source, codex/aim-straight-followup-20261013. The six uncommitted notification files remain in the original checkout; inventory layout and input-editor changes remain pending and were not included.

## Implemented

- Removed AimCursor and AimMouseMixin, including frozen look, Alt rotation and cursor movement capture. Vanilla mouse look is restored; smart cast remains. Legacy aim-cursor=true is ignored. The pre-existing Alt HUD interaction remains (it is not camera rotation).
- Screen-centre look selects a point, then an independent ray starts at the character eyes. The second ray clips walls even when the shoulder camera sees around them. Click confirmation recomputes instead of using a stale preview; existing LookAndOnGround precedes the existing cast request.
- Straight line rendering no longer calls terrain height projection: an unbent planar arrow and fixed-width envelope rotate with the full 3D direction, including vertical/air aiming. Only tint and range-ring decoration pulse/rotate; boundary geometry does not.
- Groundless targets fall back to the eye ray at full range. Round markers are flat and do not disappear simply because no ground exists. Horizontal cone geometry follows the server cone's angle/radius.
- Separated server target-ray range from effect length: crimson_breath target range15/effect radius9/angle50, firebird_hunt target10/bolt12/width3.2. tidal_wave starts at feet+1 as BoltMech does, not eye height.
- Restored maximum-range ring/text. Smaller spell/left-click/right-click hint is above food/armour; black backing removed.

## Verification

Hosting Java21 only: `./gradlew.bat -p magic-codex-fabric test remapJar --offline --no-daemon`, JAVA_HOME=C:/Program Files/Java/jdk-21.0.10 and GRADLE_USER_HOME=C:/Chacademi/tools/gradle-cache. **302 tests, 0 failures/errors/skips**. Eight new tests cover planar/vertical/short-wall geometry, fixed endpoints, sky reach, old cursor settings and canonical server range contracts. Read-only operating server range comparison and JAR hash are in ../aim-evidence.json.

An intermediate compile caught a command-parenthesis typo; a new range test initially used the incomplete Paper bootstrap YAML, then was corrected to the canonical magic-mythic-paper/server-config/spell-runtime.yml. Both were rerun successfully. Existing deprecated/unchecked Gradle warnings remain.

## Limits and pending assets

The supplied two Library IDs resolved only metadata; their pixels were not obtained via a supported Windows path. No helper repair, raw transfer URL workaround or image generation was used. Parent-prepared PNGs are not yet received/integrated. Existing band.png was viewed and reused for tinted geometry; final reference matching needs the supplied images and in-game inspection.

This is a range/initial-direction preview, not a prediction of homing, return or bounce trajectories. Server protection, live target motion and delayed cast mechanics remain authoritative and may change outcomes. Bukkit/vanilla collision timing, projectile .4-block stepping, protection filters and real multiplayer rendering have not been empirically compared in-game; do not claim exact dynamic hit prediction from these tests. No server mechanics or permissions were weakened.

No push, server restart, personal replacement or public launcher update was performed. Changes are deliberately left reviewable in this isolated checkout.
