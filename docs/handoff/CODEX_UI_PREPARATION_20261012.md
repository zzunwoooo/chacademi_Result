> Superseded by [final integrated handoff](CODEX_JOURNAL_INPUT_RELEASE_20261012.md); approved PNGs acquired and installed.

# UI/input preparation and Library blocker - 2026-10-12

Status: preparation only, NOT deployed; the requested complete quest UI is not implemented yet.

## Input preparation (separate commit)
Existing keyboard layout preserved. Left Shift(340)/Ctrl(341) allowed; both Alt, right modifiers and reserved movement/UI keys remain denied. Only thumb buttons 3/4 enter casting as stored positive IDs 1003/1004, preserving version-1 slots/key integers. Left/right/middle/other mouse buttons never enter this route. Pressing a thumb button in the existing key settings opens its spell picker. Common gameplay gate prevents casts in chat, menus, overlays or unfocused windows. Existing six-slot limit, permission and cooldown paths stay in place. KeyBinding suppression distinguishes mouse from keyboard. Picker hover shows short description using the existing overlay/font style.

PNG mouse rendering/hit outlines remain blocked, not implemented. Do not deploy this preparation as the finished approved UI.

## Story metadata preparation (separate commit)
config/chaca_dialogue/<id>/dialogue.yml accepts optional top-level description: user-authored journal prose. Existing title remains authoritative; description defaults to empty. No reference-example text is inserted into content. Legacy Dialogue constructors remain compatible. No progress store, reward, affinity or server commands changed. Metadata is parsed only; unified record display/persistence/admin editing remains pending.

Existing source distinction must be retained: QuestStore codex_quest_progress, DialogueStore codex_story_state, Story plugin progress.yml, and client LocalDialogueStore _progress/<uuid>.properties are different ledgers. Never copy completion into reward records or replay commands merely to edit journal state.

## Exact Library blocker
All four supplied quest/reference files and keybinding_mouse_v2.png were resolved by the official Library prepare_materialize tool, then passed unchanged to the freshly fetched official library_file_transfer.py on the hosting Windows Python. Every final installation fails with:
`AttributeError: module 'os' has no attribute 'setxattr'`
The helper aborts before installing the destination PNG. Destination quest-assets contains no installed PNGs. No pixel verification or visual acceptance is claimed. No raw-URL or metadata-dropping workaround was used. A default mouse preparation also returned /workspace/scratch/... (not present in this Windows execution environment); preparation was repeated with an explicit real destination, yielding the same confirmed Windows xattr failure.

Need a supported Windows-capable Library transfer route or parent-provided authorized file-transfer route. Until resolved, do not claim the images were installed. Full quest categories/detail/admin/common-toast implementation and final mouse visual layout remain pending. Operational restart is coordinated with the parent; nothing from these preparations was installed.
