package kr.kkaegul.client.mixin;

import kr.kkaegul.client.ui.HangulInput;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 윈도우 한글 입력기가 조합 안 된 자모(ㄸ,ㅏ,ㅇ…)를 하나씩 보내면 채팅 등 입력칸에 "ㄸㅏㅇㅋㅗㅇ" 처럼 따로 들어간다.
 * 자모가 오면 우리 조합기로 모아서 "땅콩" 으로 바꿔 넣는다. 다른 글자·키가 오면 조합을 끝낸다.
 */
@Mixin(TextFieldWidget.class)
public abstract class TextFieldJamoMixin {
    @Shadow public abstract String getText();
    @Shadow public abstract int getCursor();
    @Shadow public abstract void write(String text);
    @Shadow public abstract void eraseCharacters(int characterOffset);

    @Unique private final HangulInput kkaegul$ime = new HangulInput();
    @Unique private int kkaegul$len;          // 칸 안에 들어가 있는 조합 중 글자 수
    @Unique private String kkaegul$snap;       // 마지막으로 우리가 바꾼 뒤의 글
    @Unique private int kkaegul$snapCursor;

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void kkaegul$jamo(char chr, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        // 다른 곳에서 글이 바뀌었으면 (커서 이동·지움 등) 조합을 끝냄
        if (kkaegul$snap == null || !kkaegul$snap.equals(getText()) || kkaegul$snapCursor != getCursor()) {
            kkaegul$ime.flush();
            kkaegul$len = 0;
        }
        if (chr < 0x3131 || chr > 0x3163) {   // 자모가 아니면 조합 끝, 원래대로 처리
            kkaegul$ime.flush();
            kkaegul$len = 0;
            kkaegul$snap = null;
            return;
        }
        if (kkaegul$len > 0) eraseCharacters(-kkaegul$len);
        String done = kkaegul$ime.feed(chr);
        String now = kkaegul$ime.composing();
        write(done + now);
        kkaegul$len = now.length();
        kkaegul$snap = getText();
        kkaegul$snapCursor = getCursor();
        cir.setReturnValue(true);
    }
}
