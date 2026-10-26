package school.magiccodex.party;

import java.util.*;

/** Accessed only on the plugin's serial worker. Copy before durable mutations. */
final class PartyBook {
    record Party(UUID id, UUID leader, List<UUID> members) {
        Party { Objects.requireNonNull(id); Objects.requireNonNull(leader); members=List.copyOf(members);
            if(members.isEmpty() || members.size()>64 || !members.contains(leader) || new HashSet<>(members).size()!=members.size()) throw new IllegalArgumentException("Invalid party"); }
    }
    record Invite(UUID party, UUID inviter, UUID target, long expires) {}
    final Map<UUID,Party> parties=new LinkedHashMap<>();
    final Map<UUID,UUID> membership=new HashMap<>();
    final List<Invite> invites=new ArrayList<>();
    final int capacity;
    PartyBook(int capacity) { if(capacity<2||capacity>64)throw new IllegalArgumentException("capacity");this.capacity=capacity; }
    PartyBook copy(){var b=new PartyBook(capacity);parties.values().forEach(b::restore);b.invites.addAll(invites);return b;}
    void restore(Party p){if(parties.containsKey(p.id()))throw new IllegalArgumentException("Duplicate party");for(UUID m:p.members())if(membership.containsKey(m))throw new IllegalArgumentException("Duplicate member");parties.put(p.id(),p);p.members().forEach(m->membership.put(m,p.id()));}
    Party party(UUID member){UUID id=membership.get(member);return id==null?null:parties.get(id);}
    Party require(UUID member){var p=party(member);if(p==null)throw new IllegalArgumentException("소속된 파티가 없습니다.");return p;}
    Party leader(UUID member){var p=require(member);if(!p.leader().equals(member))throw new IllegalArgumentException("파티장만 사용할 수 있습니다.");return p;}
    Party create(UUID actor){if(party(actor)!=null)throw new IllegalArgumentException("이미 파티에 소속되어 있습니다.");var p=new Party(UUID.randomUUID(),actor,List.of(actor));restore(p);invites.removeIf(i->i.target().equals(actor));return p;}
    void expire(long now){invites.removeIf(i->i.expires()<=now||!parties.containsKey(i.party()));}
    Invite invite(UUID actor,UUID target,long now){
        expire(now);var p=leader(actor);
        if(actor.equals(target))throw new IllegalArgumentException("자신은 초대할 수 없습니다.");
        if(party(target)!=null)throw new IllegalArgumentException("상대가 이미 파티에 소속되어 있습니다.");
        if(p.members().size()>=capacity)throw new IllegalArgumentException("파티 정원이 가득 찼습니다.");
        if(invites.stream().anyMatch(i->i.party().equals(p.id())&&i.target().equals(target)))throw new IllegalArgumentException("이미 유효한 초대가 있습니다.");
        if(invites.stream().filter(i->i.party().equals(p.id())).count()>=16 || invites.stream().filter(i->i.target().equals(target)).count()>=16)throw new IllegalArgumentException("대기 중인 초대가 너무 많습니다.");
        var i=new Invite(p.id(),actor,target,now+120_000);invites.add(i);return i;
    }
    Invite invitation(UUID target,UUID inviter,long now){
        expire(now);var matches=invites.stream().filter(i->i.target().equals(target)&&(inviter==null||i.inviter().equals(inviter))).toList();
        if(matches.isEmpty())throw new IllegalArgumentException("유효한 파티 초대가 없습니다.");
        if(matches.size()!=1)throw new IllegalArgumentException("초대가 여러 개입니다. 초대한 사람을 지정해 주세요.");
        return matches.getFirst();
    }
    Party accept(UUID actor,UUID inviter,long now){
        if(party(actor)!=null)throw new IllegalArgumentException("이미 파티에 소속되어 있습니다.");
        var i=invitation(actor,inviter,now);var p=parties.get(i.party());
        if(p.members().size()>=capacity)throw new IllegalArgumentException("파티 정원이 가득 찼습니다.");
        var members=new ArrayList<>(p.members());members.add(actor);var next=new Party(p.id(),p.leader(),members);
        parties.put(p.id(),next);membership.put(actor,p.id());invites.removeIf(v->v.target().equals(actor));return next;
    }
    Invite deny(UUID actor,UUID inviter,long now){var i=invitation(actor,inviter,now);invites.remove(i);return i;}
    Party leave(UUID actor){
        var p=require(actor);var members=new ArrayList<>(p.members());members.remove(actor);membership.remove(actor);
        if(members.isEmpty()){parties.remove(p.id());invites.removeIf(i->i.party().equals(p.id()));}
        else {UUID next=p.leader().equals(actor)?members.getFirst():p.leader();parties.put(p.id(),new Party(p.id(),next,members));if(p.leader().equals(actor))invites.removeIf(i->i.party().equals(p.id()));}
        return p;
    }
    Party promote(UUID actor,UUID target){var p=leader(actor);if(actor.equals(target)||!p.members().contains(target))throw new IllegalArgumentException("다른 파티원을 지정해 주세요.");var next=new Party(p.id(),target,p.members());parties.put(p.id(),next);invites.removeIf(i->i.party().equals(p.id()));return next;}
    Party kick(UUID actor,UUID target){var p=leader(actor);if(actor.equals(target)||!p.members().contains(target))throw new IllegalArgumentException("다른 파티원을 지정해 주세요.");leave(target);return p;}
    Party disband(UUID actor){var p=leader(actor);p.members().forEach(membership::remove);parties.remove(p.id());invites.removeIf(i->i.party().equals(p.id()));return p;}
    List<UUID> recipients(UUID actor){return require(actor).members();}
    static String message(String raw){String text=raw.strip();if(text.isEmpty()||text.length()>240||text.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||c==0xA7))throw new IllegalArgumentException("파티 메시지는 1~240자의 일반 문자로 입력해 주세요.");return text;}
}
