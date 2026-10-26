package school.magiccodex.client;

import java.util.*;
import school.magiccodex.protocol.PetProtocol;
import school.magiccodex.protocol.PetProtocol.*;

public final class PetCatalogState {
    private List<Entry> entries=List.of();
    private List<Entry> visible=List.of();
    private final Set<String> favorites=new HashSet<>();
    private final Map<Integer,List<Entry>> pending=new HashMap<>();
    private int parts,filter;private String selected="",query="",summary="";
    private long expected;
    public void expect(long seq){expected=seq;pending.clear();parts=0;}
    public boolean accept(Response r){
        if(r.sequence()!=expected||expected==0)return false;
        if(parts!=0&&parts!=r.parts())throw new IllegalArgumentException("Conflicting parts");
        parts=r.parts();pending.put(r.part(),r.entries());
        if(pending.values().stream().mapToInt(List::size).sum()>PetProtocol.MAX_PETS)throw new IllegalArgumentException("Count");
        if(pending.size()!=parts)return false;
        var next=new ArrayList<Entry>();var ids=new HashSet<String>();
        for(int i=0;i<parts;i++)for(var e:pending.get(i)){if(!ids.add(e.id()))throw new IllegalArgumentException("Duplicate");next.add(e);}
        entries=List.copyOf(next);summary=r.summary();expected=0;pending.clear();rebuild();return true;
    }
    public void reset(){clearCatalog();favorites.clear();filter=0;query="";}
    public void clearCatalog(){summary="";entries=List.of();visible=List.of();pending.clear();expected=0;parts=0;selected="";}
    public List<Entry> visible(){return visible;}
    /** 서버가 보낸 도감 체력 한 줄 (없으면 ""). */
    public String summary(){return summary;}
    private void rebuild(){visible=entries.stream().filter(Entry::owned).filter(e->query.isEmpty()||searchable(e.name()).contains(query)||searchable(e.id()).contains(query)).filter(e->filter==0||e.stars()==filter).sorted(Comparator.<Entry,Boolean>comparing(e->!favorites.contains(e.id())).thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id)).toList();ensureSelection();}
    private static String searchable(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    public void search(String value){query=searchable(value.strip());rebuild();}
    public String query(){return query;}
    public void favorites(Collection<String> values){favorites.clear();values.stream().filter(PetProtocol::validId).limit(PetProtocol.MAX_PETS).forEach(favorites::add);rebuild();}
    public Set<String> favorites(){return Set.copyOf(favorites);}
    public boolean favorite(String id){return favorites.contains(id);}
    public void toggle(String id){if(!PetProtocol.validId(id))return;if(!favorites.remove(id)&&favorites.size()<PetProtocol.MAX_PETS)favorites.add(id);rebuild();}
    public int filter(){return filter;}
    public void filter(int value){filter=Math.clamp(value,0,3);rebuild();}
    public Entry selected(){var list=visible();return list.stream().filter(e->e.id().equals(selected)).findFirst().orElse(list.isEmpty()?null:list.getFirst());}
    public int index(){var list=visible();for(int i=0;i<list.size();i++)if(list.get(i).id().equals(selected))return i;return 0;}
    public void step(int direction){var list=visible();if(!list.isEmpty())selected=list.get(Math.floorMod(index()+direction,list.size())).id();}
    private void ensureSelection(){var list=visible();if(list.stream().noneMatch(e->e.id().equals(selected)))selected=list.isEmpty()?"":list.getFirst().id();}
}
