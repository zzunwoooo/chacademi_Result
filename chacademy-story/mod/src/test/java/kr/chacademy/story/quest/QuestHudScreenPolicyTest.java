package kr.chacademy.story.quest;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class QuestHudScreenPolicyTest {
 @Test void ctrlCursorAndChatKeepHudAndFoldButtonEligible(){assertTrue(QuestHudScreenPolicy.visible(true,"chat"));assertTrue(QuestHudScreenPolicy.visible(false,"school.magiccodex.client.HudCursorScreen"));}
 @Test void inventoryOtherScreensAndMissingOptionalModDoNotExposeHud(){assertFalse(QuestHudScreenPolicy.visible(false,"net.minecraft.client.gui.screens.inventory.InventoryScreen"));assertFalse(QuestHudScreenPolicy.visible(false,"school.magiccodex.client.SettingsScreen"));assertFalse(QuestHudScreenPolicy.visible(false,null));}
}
