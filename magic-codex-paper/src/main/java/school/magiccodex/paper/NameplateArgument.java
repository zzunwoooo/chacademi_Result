package school.magiccodex.paper;
import com.mojang.brigadier.*;import com.mojang.brigadier.arguments.*;import com.mojang.brigadier.exceptions.*;import io.papermc.paper.command.brigadier.argument.CustomArgumentType;import java.util.List;
/** Reuse the hub's Unicode word parser, while retaining quoted multi-word names. */
final class NameplateArgument implements CustomArgumentType<String,String> {
 public String parse(StringReader reader)throws CommandSyntaxException{if(reader.canRead()&&(reader.peek()=='"'||reader.peek()=='\''))return reader.readString();return new ChacaArguments.Word(List::of).parse(reader);}
 public ArgumentType<String> getNativeType(){return StringArgumentType.string();}
}
