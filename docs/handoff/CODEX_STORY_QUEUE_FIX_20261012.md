# Story failed-cutscene queue fix - 2026-10-12

Status: built/tested, NOT deployed. Based on integrated e81808946a59c09cc7c069b2ee58dd24f9ff1b86. School/wild remain Story plugin0.1.2; personal Story remains0.1.2. No runtime restart, forced client close, queue-clearing command, user YAML or player-record write was performed during this investigation.

## Proven cause

School dialogues/server_command.yml is correctly named UTF-8 (no BOM), and contains commands.ch1-3 with the user's updated magic_tutorial cutscene command. The school plugin SHA256 is b2422474035b436e210498a978f068f83b6c22291e035c5755375a3525f866c6; personal Story SHA256 is256b703b3ec5293f701c901ab9ebde8e6eb1970dfb3fffce60c12617fbb4b6e8. Both match the deployed0.1.2 artifacts.

Host/client log times:19:40:16 the old test cutscene failed with missing content. StoryPlugin.STORY_FAIL set active.sent=false and active.parked=true but left active=test. pump returned whenever parked, so every later cutscene queued forever. Changing the command registry and reloading does not clear active; the wrong state was outside that registry.19:41:11 server registry reload reported1 dialogue ID and the actual client replied Reload OK for both content categories.19:41:53 dialogue completion dispatched magic_tutorial as console successfully, but it queued behind test;19:42:32 duplicate request was rejected. A read-only progress snapshot confirmed active=test, queue=[magic_tutorial,ch1_ashen_night]. Direct commands failed for the same reason, not name resolution or missing tutorial content.

The user independently ran the cutscene stop command at19:48:35. It clears only active then pumps the next queued request. The client immediately logged magic_tutorial loaded (3 scenes,19.2sec). After another ch1-3 completion the client logged the same cutscene loaded at19:49:13. This proves both direct queued playback and completion-to-cutscene work once the old blocker is removed. No request to re-enter YAML is necessary.

## Minimal server-only change

Story plugin0.1.3 cancels an acknowledged failed cutscene request without recording completion or running its end rewards, then advances the queue. Applies to client missing/broken/load-timeout failure, with existing explicit finish-if-client-content-missing=true compatibility behavior retained. The normal default false now cancels instead of parking indefinitely. Exhausted interruption/min-duration retries likewise cancel only that request without end effects. Direct play messages explicitly say when queued behind another request.

Stop and normal completion use the same identity-checked active-request removal. Client failure must match a sent active cutscene and ID; a late failure for missing_id cannot remove magic_tutorial, and an old Request object cannot remove a new request. The existing cutscene wire protocol contains ID rather than a per-play nonce; this patch does not claim to solve same-ID stale-packet ambiguity by changing that protocol. Dialogue completion retains the separate player-bound random session token/ready ACK/consume-before-dispatch checks.

Lifecycle audit: successful finish still removes active then runs its durable normal effects; explicit stop/cancel never awards completion; disconnect resets transient transport flags and preserves legitimate unfinished playback for reconnect; reload keeps playing content and progress instead of clearing everyone's queues. Ordinary screen interruption retains bounded retries; terminal content failure/retry exhaustion no longer parks a blocker. No arbitrary playback-duration timeout was introduced for legitimate long or menu-suspended cutscenes. Client screen behavior is unchanged: Runner signals completion before Presenter closes its screen, and CutscenePlayback defers while a dialogue/other screen remains. The mixed regression test checks that Presenter closes before deferred cutscene delivery is eligible.

## Verification and application

Hosting Java21 test/build passed: {"plugin": {"tests": 64, "failures": 0, "errors": 0, "skipped": 0}, "mod": {"tests": 45, "failures": 0, "errors": 0, "skipped": 0}}. New tests cover missing_id -> magic_tutorial without stop; old parked test -> stop -> next request; wrong/unsent failure rejection; stale request identity; normal playing queue; reconnect transient reset; real DialogueRunner/DialoguePresenter + Fabric/Paper codecs + server session authorization and queue transition, two completions/two command dispatches with one first-completion claim. Only graphics surface and Bukkit console dispatch are test adapters; no claim of running the new JAR inside the live game.

Prepared server artifact: C:/Chacademi/staging/magic-protection-20261011-task5/story-queue-fix-20261012/chacademy-story-plugin-0.1.3.jar
SHA256: 870d48d3227ef655e0c2e62db78712c36a4ad9a45df972d431c70c2ac7c6e7e6

Minimum permanent application: replace only Story server plugin on school/wild during the next authorized normal restart with fresh zero-player checks and backups. No UI or Story client replacement needed. Do not deploy the public mod build produced for tests; personal private-font0.1.2 stays installed. Preserve the user's current server_command.yml and all progress/reward files. A config reload cannot load new Java code. The user has already unblocked the live queue, so no further recovery command is needed now.
