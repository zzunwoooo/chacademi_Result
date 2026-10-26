# VFX 팩 올림 (Claude, 2026-10-26) — GPT 배포용

오너 PC `바탕화면\차카데미 vfx_Claude` 에만 있던 팩을 저장소 `portable-vfx-runtime/assets/<팩>/` 에 올렸다 (각 팩: vfx.json · textures/ · NOTES.md, 미리보기 html·schema 는 뺌).
기준: 마법 카탈로그·바인딩·코드가 부르는 `claude:<팩>/...` 중 런처 v0.1.21 client-assets 와 저장소에 없던 것 + 런처판과 vfx.json 이 다른(다시 만든) 어둠 팩.

## 배치
클라 런처 client-assets 의 `config/portablevfx/effects/<팩>/` 에 폴더째 넣는다 (이미 있는 팩은 **교체**). 서버에는 필요 없음 (서버는 id 만 보냄).
런처 client-assets 를 새로 만들면 `deployment/external-vfx-assets.json` 목록도 그 결과로 다시 쓴다 (path·bytes·sha256).

## 새 팩 77개 (마법 300개 확장의 신규 마법·생활 마법 + 졸음)
AbyssGate, AirCannon, AirChoke, AlchemyPreheat, AuroraVeil, BlazePillar, BlessedRibbon, BottleFill, BoulderCharge, BroomAfterburner, CalmRipple, CampfireRest, CrimsonSpiral, CropSnare, CropSurvey, CrystalCoat, DreadDream, Drowsiness, DuskOrb, EbbMark, EmberPuff, EnlightenHint, EruptionLine, FateLink, FaultRift, FlameEmperor, FlameJavelin, FlameWheel, FlashBlink, FreezeField, FrostPath, GaleCombo, HaloThrow, HarvestBell, HeartRead, HeatHaze, HeavenCyclone, HerdingBreeze, HexDoll, HydroBurst, IceBloom, IceSerpent, IceShard, InsightFlash, LeviathanRise, LightStep, MemoryLantern, MiniTwister, PackSort, PrismCage, PrismShot, ReplantTouch, SandFlick, ScaleAppraisal, ScorchLock, SeasonSense, ShadowFang, ShadowMire, ShadowStep, ShadowVault, SignalFlare, SkyDive, SparkFlick, StormEye, SunlanceDrop, SweetSleep, Tailwind, Thundercloud, TideSlash, TitanStride, TreasureScent, VacuumBurst, VolcanicBarrage, WardCandle, WaterGlimpse, WindNeedle, WraithMarch

## 교체 팩 18개 (어둠 마법 다시 만든 것, 런처판 vfx.json 과 다름)
BellOfFear, CurseTransfer, Discord, EchoReading, ForbiddenContract, GhostTool, HealingSeal, HungryShadow, InkFingertip, InvertedOffering, LifeSiphon, LoosenGrasp, NightSight, PainDeferral, SealingThread, ShadowNail, WoundAccounting, misfortune_whisper

## 그 밖에 이미 저장소에 있던 팩 (전에 올림, 런처에 없으면 같이 넣기)
PetPaw, JackInTheBox, ArtifactCompanions, WaypointGlobe, BroomTrails, BroomTrailsWood, BroomTrailsUnique, BroomTrailsLegend, ElementReactions, HolyTaming, 기믹 팩(ConduitTurn·DimensionMark·LightBeam·MirrorTune·PrismTint·Rewind·StatueWake·SteamIgnition·StoneWeight·ThawingHeat·VineAwaken·WaterLevel)
