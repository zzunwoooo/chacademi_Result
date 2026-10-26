package kr.chacademy.story.quest;
/** Optional MagicCodex cursor screen is identified by stable class name; chat remains supported. */
final class QuestHudScreenPolicy {
 private QuestHudScreenPolicy(){}
 static boolean visible(boolean chat,String className){return chat||"school.magiccodex.client.HudCursorScreen".equals(className);}
}
