package school.magiccodex.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Six local bindings; never grants permission or executes a command. */
public final class SpellKeySettings {
    public static final int LIMIT = 6;
    public record Binding(String spellId, int keyCode) {
        public boolean empty() { return spellId.isEmpty(); }
    }
    private static final Binding EMPTY = new Binding("", -1);
    private final List<Binding> slots = new ArrayList<>(Collections.nCopies(LIMIT, EMPTY));
    private final List<String> registrationOrder = new ArrayList<>();
    private void reconcileOrder() {
        registrationOrder.removeIf(id -> slotOfSpell(id)<0);
        for(var binding:slots) if(!binding.empty() && !registrationOrder.contains(binding.spellId()))
            registrationOrder.add(binding.spellId());
    }
    public List<Binding> orderedSlots() {
        reconcileOrder();
        var result=new ArrayList<Binding>();
        for(String id:registrationOrder) result.add(get(slotOfSpell(id)));
        while(result.size()<LIMIT) result.add(EMPTY);
        return List.copyOf(result);
    }
    public List<Binding> slots() { return List.copyOf(slots); }
    public Binding get(int slot) { return slots.get(slot); }
    public SpellKeySettings copy() {
        var copy = new SpellKeySettings(); copy.slots.clear(); copy.slots.addAll(slots); copy.registrationOrder.addAll(registrationOrder); return copy;
    }
    public boolean sameAs(SpellKeySettings other) { return slots.equals(other.slots) && orderedSlots().equals(other.orderedSlots()); }
    public void clear(int slot) { slots.set(slot, EMPTY); reconcileOrder(); }
    public void clear() { Collections.fill(slots, EMPTY); registrationOrder.clear(); }
    public void select(int slot, String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("올바르지 않은 마법 ID입니다.");
        for (int i=0;i<LIMIT;i++) if(i!=slot && get(i).spellId().equals(id))
            throw new IllegalArgumentException("이미 다른 슬롯에 등록한 마법입니다.");
        slots.set(slot,new Binding(id,get(slot).keyCode())); reconcileOrder();
    }
    public void bind(int slot,int code) {
        if(get(slot).empty()) throw new IllegalArgumentException("먼저 마법을 선택해 주세요.");
        if(!KeySettingsLayout.allowed(code)) throw new IllegalArgumentException(KeySettingsLayout.RESERVED_MESSAGE);
        for(int i=0;i<LIMIT;i++) if(i!=slot && !get(i).empty() && get(i).keyCode()==code)
            throw new IllegalArgumentException("이미 다른 마법에 지정한 키입니다.");
        slots.set(slot,new Binding(get(slot).spellId(),code));
    }
    /** Slot holding a spell on this key, or -1. Empty slots never match. */
    public int slotOfKey(int code) {
        for(int i=0;i<LIMIT;i++) if(!get(i).empty() && get(i).keyCode()==code) return i;
        return -1;
    }
    public int slotOfSpell(String id) {
        for(int i=0;i<LIMIT;i++) if(!get(i).empty() && get(i).spellId().equals(id)) return i;
        return -1;
    }
    public int count() { return (int)slots.stream().filter(b -> !b.empty()).count(); }
    /**
     * Keyboard-first registration. The key decides the target:
     * a key already in use keeps its slot and changes spell, a spell already registered elsewhere
     * moves to this key (keeping its slot order), otherwise the first empty slot is used.
     * Never exceeds {@link #LIMIT}. Returns the slot that now holds the binding.
     */
    public int assign(int code,String id) {
        if(id==null || !id.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("올바르지 않은 마법 ID입니다.");
        if(!KeySettingsLayout.allowed(code)) throw new IllegalArgumentException(KeySettingsLayout.RESERVED_MESSAGE);
        int keySlot=slotOfKey(code), spellSlot=slotOfSpell(id);
        int target=keySlot>=0?keySlot:spellSlot;
        if(target<0) for(int i=0;i<LIMIT && target<0;i++) if(get(i).empty()) target=i;
        if(target<0) throw new IllegalArgumentException("마법은 최대 "+LIMIT+"개까지 등록할 수 있습니다. 우클릭으로 하나를 해제해 주세요.");
        if(spellSlot>=0 && spellSlot!=target) slots.set(spellSlot,EMPTY);
        slots.set(target,new Binding(id,code)); reconcileOrder();
        return target;
    }
    /** Right-click on the keyboard. Returns the cleared slot, or -1 when the key was free. */
    public int clearKey(int code) {
        int slot=slotOfKey(code); if(slot>=0) clear(slot); return slot;
    }
    /** Reorders the HUD bar; bindings (spell+key) travel together. */
    public void swap(int a,int b) {
        Objects.checkIndex(a,LIMIT); Objects.checkIndex(b,LIMIT);
        if(a!=b) Collections.swap(slots,a,b);
    }
    /**
     * Moves HUD positions a and b (the order of {@link #orderedSlots()}); spell and key travel together.
     * An empty position counts as "after the last spell".
     */
    public void swapOrder(int a,int b) {
        Objects.checkIndex(a,LIMIT); Objects.checkIndex(b,LIMIT);
        reconcileOrder();
        var ids=new ArrayList<String>(registrationOrder);
        while(ids.size()<LIMIT) ids.add("");
        Collections.swap(ids,a,b);
        registrationOrder.clear();
        for(String id:ids) if(!id.isEmpty()) registrationOrder.add(id);
    }
    public boolean complete() { return slots.stream().allMatch(b -> b.empty() || KeySettingsLayout.allowed(b.keyCode())); }
    public static SpellKeySettings load(Path path) throws IOException {
        var result = new SpellKeySettings();
        if(!Files.exists(path)) return result;
        if(Files.size(path)>16384) throw new IOException("키 설정 파일이 너무 큽니다.");
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setCodePointLimit(16384);
        try(var reader=Files.newBufferedReader(path)) {
            Object root = new Yaml(new SafeConstructor(options)).load(reader);
            if(!(root instanceof Map<?,?> map) || !Integer.valueOf(1).equals(map.get("version"))
                    || !(map.get("slots") instanceof List<?> values) || values.size()!=LIMIT)
                throw new IllegalArgumentException("지원하지 않는 키 설정 형식입니다.");
            for(int i=0;i<LIMIT;i++) {
                Object value=values.get(i);
                if(value==null) continue;
                if(!(value instanceof Map<?,?> binding) || !(binding.get("spell") instanceof String id)
                        || !(binding.get("key") instanceof Integer code)) throw new IllegalArgumentException("올바르지 않은 키 설정입니다.");
                result.select(i,id);
                // A key that can no longer hold a spell (WASD) leaves the spell without a key until the user picks another.
                if(code!=-1&&KeySettingsLayout.allowed(code))result.bind(i,code);
            }
            if(map.get("registration-order") instanceof List<?> order) {
                var verified=new ArrayList<String>();
                for(Object value:order) {
                    if(!(value instanceof String id) || result.slotOfSpell(id)<0 || verified.contains(id))
                        throw new IllegalArgumentException("Invalid registration order");
                    verified.add(id);
                }
                result.registrationOrder.clear(); result.registrationOrder.addAll(verified);
            }
            result.reconcileOrder();
            return result;
        } catch(RuntimeException error) { throw new IOException("키 설정 파일을 읽지 못했습니다: " + error.getMessage(),error); }
    }
    /** Commit atomically so a failed write cannot truncate the last working settings. */
    public void save(Path path) throws IOException {
        if(!complete()) throw new IOException("키가 없는 마법이 있습니다. 해당 칸을 우클릭으로 해제하거나 키를 눌러 다시 지정해 주세요.");
        var root=new LinkedHashMap<String,Object>(); root.put("version",1);
        var values=new ArrayList<Object>();
        for(var b:slots) {
            if(b.empty()) values.add(null);
            else { var m=new LinkedHashMap<String,Object>(); m.put("spell",b.spellId()); m.put("key",b.keyCode()); values.add(m); }
        }
        root.put("slots",values);
        reconcileOrder(); root.put("registration-order",List.copyOf(registrationOrder));
        var options=new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path target=path.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),"key-settings-",".tmp");
        try {
            Files.writeString(temp,new Yaml(options).dump(root));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
