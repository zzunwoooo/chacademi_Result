package kr.chacademi.chatlayout.mixin;
import com.ebicep.chatplus.features.chattabs.ChatTab;
import kr.chacademi.chatlayout.ChatPresentation;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Runs after routing, before wrapping, also when stored history is reflowed. */
@Mixin(value=ChatTab.class,remap=false)
public abstract class ChatPresentationMixin {
 @ModifyVariable(method="addNewDisplayMessage",at=@At("HEAD"),argsOnly=true,ordinal=0,remap=false,require=1)
 private MutableComponent chacademi$display(MutableComponent component){return ChatPresentation.display(component);}
}
