package dev.portablevfx.client.claude;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
/** Reviewed immutable exports only. Arbitrary or edited packs retain the standard 16/64 bounds.
 * No asset metadata can raise a limit. Particle, frame, cache and GPU budgets remain unchanged. */
final class AuditedPackLimits {
    record Limits(int systems,int layers) {}
    private static final Limits STANDARD=new Limits(16,64);
    private static final Map<String,Limits> REVIEWED=Map.ofEntries(
        Map.entry("4aaa77707430d5e69025e59676a36bb525b4cec48410f578ddb7632ab237348b",new Limits(19,64)),
        Map.entry("984399ad237fb5e0c97d6946b4bba5a96a3e681188895d58ed0d3181cc7c0ae4",new Limits(16,101)),
        Map.entry("804a0a81815303ef95ec06f22f58531d1ada1ef2d93f94e340574a97a904519d",new Limits(16,68)),
        Map.entry("33f0409df5704e89030d31cde3908e39ab4a6a369e4577e8862ae5b24ce7df0f",new Limits(16,70))
    );
    static Limits forEffect(JsonObject effect){
        // The loader adds this selector per definition; it does not change authored content.
        JsonObject authored=effect;
        if(effect.has("portableVfxSystem")){authored=effect.deepCopy();authored.remove("portableVfxSystem");}
        try{
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authored.toString().getBytes(StandardCharsets.UTF_8)));
            return REVIEWED.getOrDefault(hash,STANDARD);
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
