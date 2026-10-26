package kr.chacademy.npc.dialogue;

import kr.chacademy.npc.ChacaNpcPlugin;
import kr.chacademy.npc.core.Defs.Button;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import school.magiccodex.npctalk.NpcTalkProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MagicCodex HUD 대화 채널. 버전 handshake(HELLO/HELLO_ACK)를 마친 플레이어만 HUD 화면을 쓴다.
 * 세션 토큰·요청 순번이 맞지 않는 패킷은 버린다.
 */
public final class NpcTalkChannel implements PluginMessageListener, Listener, DialogueView {

    private final ChacaNpcPlugin plugin;
    private final Map<UUID, Integer> capable = new ConcurrentHashMap<>();
    private final Map<UUID, Long> gate = new ConcurrentHashMap<>();

    public NpcTalkChannel(ChacaNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, NpcTalkProtocol.REQUEST, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, NpcTalkProtocol.RESPONSE);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, NpcTalkProtocol.REQUEST, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, NpcTalkProtocol.RESPONSE);
        capable.clear();
    }

    /** handshake를 마쳤고 지금도 응답 채널을 듣고 있으면 HUD 대화 가능. */
    public boolean isCapable(Player p) {
        Integer v = capable.get(p.getUniqueId());
        return v != null && v == NpcTalkProtocol.VERSION
                && p.getListeningPluginChannels().contains(NpcTalkProtocol.RESPONSE);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        capable.remove(e.getPlayer().getUniqueId());
        gate.remove(e.getPlayer().getUniqueId());
    }

    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    /** 클라이언트 검사 한도에 맞게 잘라서 보낸다 (긴 이름·버튼·대사 때문에 창이 안 열리는 일 방지). */
    private void send(Player p, NpcTalkProtocol.Response r) {
        if (!p.isOnline() || !plugin.isEnabled()) {
            return; // 꺼지는 중(onDisable 의 closeAll)에는 패킷을 보낼 수 없다 — 경고 없이 건너뜀
        }
        List<NpcTalkProtocol.Button> bs = new ArrayList<>();
        for (NpcTalkProtocol.Button b : r.buttons()) {
            if (bs.size() < NpcTalkProtocol.MAX_BUTTONS && b.id().matches("[a-z0-9_-]{1,32}")) {
                bs.add(new NpcTalkProtocol.Button(b.id(), clamp(b.label(), 40)));
            }
        }
        String portrait = r.portrait() == null || !r.portrait().matches("[a-z0-9_/-]{0,120}") ? "" : r.portrait();
        r = new NpcTalkProtocol.Response(r.op(), r.session(), Math.max(0, r.seq()), clamp(r.speaker(), 64), portrait,
                clamp(r.text(), NpcTalkProtocol.MAX_LINE_CHARS), bs, Math.max(0, Math.min(NpcTalkProtocol.MAX_HEARTS, r.hearts())),
                r.input(), r.token() == null ? "" : r.token());
        try {
            p.sendPluginMessage(plugin, NpcTalkProtocol.RESPONSE, NpcTalkProtocol.encode(r));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[ChacaNPC] HUD 패킷 인코딩 실패: " + e.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!NpcTalkProtocol.REQUEST.equals(channel)) {
            return;
        }
        NpcTalkProtocol.Request r;
        try {
            r = NpcTalkProtocol.request(bytes);
        } catch (IllegalArgumentException e) {
            return; // 형식·길이·버전이 맞지 않으면 버림
        }
        if (r.op() == NpcTalkProtocol.C_SAY || r.op() == NpcTalkProtocol.C_BUTTON || r.op() == NpcTalkProtocol.C_GIFT) {
            // 말하기·버튼만 도배 방지 (닫기·퀘스트 응답·handshake는 항상 처리)
            long now = System.currentTimeMillis();
            Long last = gate.get(p.getUniqueId());
            if (last != null && now - last < 150) {
                DialogueSession s = plugin.dialogue().session(p.getUniqueId());
                if (s != null && s.view == this && s.token().equals(r.session())) {
                    send(p, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_INFO, s.token(), r.seq(), "(조금만 천천히 말해 주세요)"));
                }
                return;
            }
            gate.put(p.getUniqueId(), now);
        }
        if (r.op() == NpcTalkProtocol.C_HELLO) {
            if (r.seq() == NpcTalkProtocol.VERSION) {
                capable.put(p.getUniqueId(), r.seq());
                send(p, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_HELLO_ACK, "", NpcTalkProtocol.VERSION, "ok"));
            } else {
                capable.remove(p.getUniqueId());
            }
            return;
        }
        DialogueSession s = plugin.dialogue().session(p.getUniqueId());
        if (s == null || s.view != this || !s.token().equals(r.session())) {
            return; // 닫혔거나 다른 NPC로 바뀐 세션의 요청
        }
        if (r.op() == NpcTalkProtocol.C_CLOSE) {
            plugin.dialogue().close(p, false);
            return;
        }
        if (r.seq() <= s.lastClientSeq()) {
            return; // 이미 처리한 순번
        }
        s.setLastClientSeq(r.seq());
        switch (r.op()) {
            case NpcTalkProtocol.C_SAY -> plugin.dialogue().input(p, r.text(), null, r.seq());
            case NpcTalkProtocol.C_BUTTON -> plugin.dialogue().input(p, null, r.text(), r.seq());
            case NpcTalkProtocol.C_QUEST -> plugin.dialogue().answerQuest(p, r.text(), r.accept(), r.seq());
            case NpcTalkProtocol.C_GIFT -> plugin.dialogue().gift(p, Integer.parseInt(r.text()), r.seq());
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ DialogueView

    private static String portrait(DialogueSession s) {
        String p = s.character().portrait();
        return p == null || !p.matches("[a-z0-9_/-]{0,120}") ? "" : p;
    }

    @Override
    public void open(Player player, DialogueSession s, String greeting, List<Button> buttons, int hearts) {
        List<NpcTalkProtocol.Button> bs = new ArrayList<>();
        for (Button b : buttons) {
            if (bs.size() < NpcTalkProtocol.MAX_BUTTONS) {
                bs.add(new NpcTalkProtocol.Button(b.id(), b.label()));
            }
        }
        send(player, new NpcTalkProtocol.Response(NpcTalkProtocol.S_OPEN, s.token(), 0, s.character().name(),
                portrait(s), greeting, bs, Math.max(0, Math.min(NpcTalkProtocol.MAX_HEARTS, hearts)), true, ""));
    }

    @Override
    public void thinking(Player player, DialogueSession s, int seq) {
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_THINKING, s.token(), seq, ""));
    }

    @Override
    public void stall(Player player, DialogueSession s, int seq, String line) {
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_STALL, s.token(), seq, line));
    }

    @Override
    public void line(Player player, DialogueSession s, int seq, String line) {
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_LINE, s.token(), seq, line));
    }

    @Override
    public void questOffer(Player player, DialogueSession s, int seq, String title, String token) {
        send(player, new NpcTalkProtocol.Response(NpcTalkProtocol.S_QUEST_OFFER, s.token(), seq, "", "",
                title.length() > 60 ? title.substring(0, 60) : title, List.of(), 0, false, token));
    }

    @Override
    public void gifts(Player player, DialogueSession s, int seq, List<GiftService.GiftItem> slots) {
        List<NpcTalkProtocol.GiftSlot> out = new java.util.ArrayList<>();
        for (GiftService.GiftItem g : slots) {
            out.add(new NpcTalkProtocol.GiftSlot(g.slot(), g.grade()));
        }
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_GIFTS, s.token(), seq, NpcTalkProtocol.giftList(out)));
    }

    @Override
    public void info(Player player, DialogueSession s, String message) {
        if (s == null) {
            player.sendMessage(message);
            return;
        }
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_INFO, s.token(), s.replySeq, message));
    }

    @Override
    public void close(Player player, DialogueSession s, String farewell) {
        // 작별 인사가 있으면 CLOSE에 실어 보낸다 (클라이언트가 타자기로 보여준 뒤 창을 닫음)
        send(player, NpcTalkProtocol.Response.simple(NpcTalkProtocol.S_CLOSE, s.token(), 0, farewell == null ? "" : farewell));
    }
}
