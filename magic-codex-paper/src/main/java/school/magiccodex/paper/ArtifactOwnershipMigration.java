package school.magiccodex.paper;
import java.nio.file.*;import java.security.MessageDigest;import java.util.*;import school.magiccodex.database.DatabaseSettings;
/** Offline operator CLI: consumes artifact-scoped private audit files, never exports identity data. */
public final class ArtifactOwnershipMigration {
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("Usage: <dedicated settings file> <school artifact TSV> <wild artifact TSV>");
  var settings=DatabaseSettings.load(Path.of(args[0]));if(!settings.mariaDb())throw new IllegalStateException("Existing MariaDB connection required");
  // Validate both sources before any SQL write. Conditional or inherited grants require operator resolution.
  var school=read(Path.of(args[1]));var wild=read(Path.of(args[2]));var store=new ArtifactOwnershipStore(()->settings.connect(null));store.initialize();
  boolean a=store.migrate("school-v1",sha(Path.of(args[1])),school),b=store.migrate("wild-v1",sha(Path.of(args[2])),wild);
  var union=new HashSet<String>();school.forEach((p,ids)->ids.forEach(id->union.add(p+":"+id)));wild.forEach((p,ids)->ids.forEach(id->union.add(p+":"+id)));
  System.out.println("Artifact migration: schoolRows="+school.values().stream().mapToInt(Set::size).sum()+" wildRows="+wild.values().stream().mapToInt(Set::size).sum()+" unionRows="+union.size()+" schoolApplied="+a+" wildApplied="+b+" complete="+store.migrationComplete());
 }
 static Map<UUID,Set<String>> read(Path path)throws Exception {
  var out=new HashMap<UUID,Set<String>>();for(String line:Files.readAllLines(path)){if(line.isBlank())continue;String[] p=line.split("\t",-1);
   if(p.length!=6||!p[0].equals("user")||!p[3].equals("true")||!p[4].equals("0")||!p[5].isEmpty()||!p[2].startsWith("magiccodex.artifact.own."))throw new IllegalStateException("Conditional, negative, inherited, or broad permission found; migration requires operator review");
   String id=p[2].substring("magiccodex.artifact.own.".length());ArtifactOwnershipStore.valid(id);out.computeIfAbsent(UUID.fromString(p[1]),k->new HashSet<>()).add(id);
  }return out;
 }
 private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
}
