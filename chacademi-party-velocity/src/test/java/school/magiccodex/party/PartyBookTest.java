package school.magiccodex.party;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
class PartyBookTest {
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
    PartyBook pair(){var book=new PartyBook(8);book.create(a);book.invite(a,b,100);book.accept(b,a,101);return book;}
    @Test void onlyInvitedPlayerCanJoin(){var book=new PartyBook(8);book.create(a);book.invite(a,b,100);assertThrows(IllegalArgumentException.class,()->book.accept(c,a,101));assertEquals(List.of(a,b),book.accept(b,a,101).members());}
    @Test void expiredInvitationCannotBeAccepted(){var book=new PartyBook(8);book.create(a);book.invite(a,b,100);assertThrows(IllegalArgumentException.class,()->book.accept(b,a,120100));}
    @Test void leaderOnlyMutations(){var book=pair();assertThrows(IllegalArgumentException.class,()->book.invite(b,c,102));assertThrows(IllegalArgumentException.class,()->book.kick(b,a));assertThrows(IllegalArgumentException.class,()->book.promote(b,a));assertThrows(IllegalArgumentException.class,()->book.disband(b));}
    @Test void capacityCheckedAtAcceptNotOnlyInvite(){var book=new PartyBook(2);book.create(a);book.invite(a,b,0);book.invite(a,c,0);book.accept(b,a,1);assertThrows(IllegalArgumentException.class,()->book.accept(c,a,2));}
    @Test void onePartyPerUserAndOtherInvitesCleared(){var book=new PartyBook(8);book.create(a);book.create(c);book.invite(a,b,0);book.invite(c,b,0);assertThrows(IllegalArgumentException.class,()->book.accept(b,null,1));book.accept(b,a,2);assertThrows(IllegalArgumentException.class,()->book.accept(b,c,3));assertTrue(book.invites.isEmpty());}
    @Test void leaderLeavesTransfersToOldestRemaining(){var book=pair();book.invite(a,c,100);book.leave(a);assertEquals(b,book.require(b).leader());assertTrue(book.invites.isEmpty());book.leave(b);assertTrue(book.parties.isEmpty());}
    @Test void promoteRevokesOldLeaderAndInvitations(){var book=pair();book.invite(a,c,100);book.promote(a,b);assertThrows(IllegalArgumentException.class,()->book.invite(a,d,101));assertThrows(IllegalArgumentException.class,()->book.accept(c,a,101));book.invite(b,d,101);}
    @Test void kickedPlayerGetsNoFurtherMessages(){var book=pair();book.kick(a,b);assertEquals(List.of(a),book.recipients(a));assertThrows(IllegalArgumentException.class,()->book.recipients(b));}
    @Test void disbandRemovesMembershipAndInvites(){var book=pair();book.invite(a,c,100);book.disband(a);assertTrue(book.membership.isEmpty());assertTrue(book.invites.isEmpty());book.create(b);}
    @Test void declineCannotRemoveAnotherPlayersInvitation(){var book=new PartyBook(8);book.create(a);book.invite(a,b,0);assertThrows(IllegalArgumentException.class,()->book.deny(c,a,1));book.deny(b,a,1);assertThrows(IllegalArgumentException.class,()->book.accept(b,a,2));}
    @Test void duplicateAndSelfInvitesRejected(){var book=new PartyBook(8);book.create(a);assertThrows(IllegalArgumentException.class,()->book.invite(a,a,0));book.invite(a,b,0);assertThrows(IllegalArgumentException.class,()->book.invite(a,b,1));}
    @Test void failedDurableCopyDoesNotMutateLiveState(){var book=pair();var next=book.copy();next.kick(a,b);assertEquals(List.of(a,b),book.recipients(a));}
    @Test void messageValidation(){assertEquals("/op someone",PartyBook.message(" /op someone "));assertThrows(IllegalArgumentException.class,()->PartyBook.message(" "));assertThrows(IllegalArgumentException.class,()->PartyBook.message("a\nb"));assertThrows(IllegalArgumentException.class,()->PartyBook.message("x".repeat(241)));assertThrows(IllegalArgumentException.class,()->PartyBook.message("\u00a7cspoof"));}
    @Test void restartRestoresMembersAndLeaderButNotInvites(@TempDir Path dir)throws Exception{var book=pair();book.promote(a,b);book.invite(b,c,0);var store=new PartyStore(dir.resolve("parties.json"));store.save(book);store.save(book);var restored=store.load(8);assertEquals(b,restored.require(a).leader());assertEquals(List.of(a,b),restored.recipients(b));assertTrue(restored.invites.isEmpty());}
    @Test void corruptStateFailsClosedAndPreservesFile(@TempDir Path dir)throws Exception{var file=dir.resolve("parties.json");Files.writeString(file,"broken");assertThrows(java.io.IOException.class,()->new PartyStore(file).load(8));assertEquals("broken",Files.readString(file));}
    @Test void duplicateMembershipRejectedOnLoad(@TempDir Path dir)throws Exception{var book=pair();var file=dir.resolve("parties.json");new PartyStore(file).save(book);String text=Files.readString(file);var root=com.google.gson.JsonParser.parseString(text).getAsJsonObject();var duplicate=root.getAsJsonArray("parties").get(0).deepCopy().getAsJsonObject();duplicate.addProperty("id",UUID.randomUUID().toString());root.getAsJsonArray("parties").add(duplicate);Files.writeString(file,root.toString());assertThrows(java.io.IOException.class,()->new PartyStore(file).load(8));}
}
