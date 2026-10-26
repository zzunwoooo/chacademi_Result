package school.magiccodex.mixin;

import com.mojang.brigadier.StringReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-10-20 명령어 정리: 서버가 한글 이름 칸(마법·NPC·경기장·빗자루 이름 등)을 Brigadier {@code word()}로 보낸다.
 * 서버 칸은 공백 전까지 아무 글자나 받지만 바닐라 클라이언트의 {@code word()}는 0-9 A-Z a-z _ - . + 만 받아
 * 한글·괄호(예: {@code 차큐스_400_(참나무)})를 빨간 오류로 표시한다. 그래서 따옴표 없는 단어에 더 많은 글자를 허용한다.
 *
 * 허용하지 않는 것: 공백, 따옴표(" '), 역슬래시, 그리고 바닐라의 다른 파서가 이 메서드로 "단어 끝"을 찾는
 * 구조 문자 {@code , : = { } [ ] ;}. 이 메서드는 {@code readUnquotedString}/{@code readString}을 통해
 * NBT(SNBT 키·값: {@code {a:1,b:[2]}}), 엔티티 선택자 옵션({@code @e[name=x,limit=1]}), 블록 상태({@code [facing=north]})
 * 파싱에도 쓰이므로, 이 문자들까지 허용하면 그 명령들이 깨진다. 좌표(~ ^ 숫자)·리소스 ID는 자체 파서라 영향 없음.
 * Brigadier는 Minecraft 라이브러리라 Fabric Loader(Knot)가 로드·변환한다. 난독화되지 않은 이름이라 remap=false.
 */
@Mixin(value = StringReader.class, remap = false)
public abstract class BrigadierUnicodeWordMixin {
    @Inject(method = "isAllowedInUnquotedString(C)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void magiccodex$unicodeWord(char c, CallbackInfoReturnable<Boolean> result) {
        if (allowedExtra(c)) result.setReturnValue(true);
    }

    private static boolean allowedExtra(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return false;
        return switch (c) {
            case '"', '\'', '\\', ',', ':', '=', '{', '}', '[', ']', ';' -> false;
            default -> true;
        };
    }
}
