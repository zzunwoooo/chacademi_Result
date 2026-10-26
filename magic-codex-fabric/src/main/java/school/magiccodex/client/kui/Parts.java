package school.magiccodex.client.kui;

import java.util.HashMap;
import java.util.Map;

/**
 * 큐디치 UI 부품 (textures/gui/kyudichi/ui/&lt;이름&gt;.png, 오너 CHACADEMI_QUIDDITCH_FULL_UI_REBUILD) and their pixel sizes.
 * keys/* are stored at 256×256 (delivered 1254). dashboard/recent_table had the example win/loss badges painted out (README rule 3).
 */
public final class Parts {
    private Parts() {}
    public static final String ROOT = "textures/gui/kyudichi/ui/";
    private static final Map<String, int[]> SIZES = build();
    private static Map<String, int[]> build() {
        var m = new HashMap<String, int[]>();
        m.put("application/main", new int[]{928, 826});
        m.put("application/match_start", new int[]{487, 90});
        m.put("application/match_start_disabled", new int[]{487, 90});
        m.put("application/match_start_hover", new int[]{487, 90});
        m.put("application/match_start_pressed", new int[]{487, 90});
        m.put("application/member_leader", new int[]{270, 347});
        m.put("application/member_normal", new int[]{271, 349});
        m.put("application/queue_cancel", new int[]{267, 61});
        m.put("application/queue_cancel_disabled", new int[]{267, 61});
        m.put("application/queue_cancel_hover", new int[]{267, 61});
        m.put("application/queue_cancel_pressed", new int[]{267, 61});
        m.put("application/queue_panel", new int[]{344, 313});
        m.put("application/rating_field", new int[]{218, 51});
        m.put("application/summary_band", new int[]{831, 109});
        m.put("dashboard/draw_badge", new int[]{102, 28});
        m.put("dashboard/loss_badge", new int[]{102, 28});
        m.put("dashboard/main", new int[]{1439, 866});
        m.put("dashboard/match_card", new int[]{1372, 174});
        m.put("dashboard/my_rank_row", new int[]{1264, 87});
        m.put("dashboard/normal_rank_row", new int[]{1205, 62});
        m.put("dashboard/profile", new int[]{509, 237});
        m.put("dashboard/recent_table", new int[]{1291, 350});
        m.put("dashboard/scrollbar", new int[]{25, 512});
        m.put("dashboard/search_button", new int[]{131, 53});
        m.put("dashboard/search_button_disabled", new int[]{131, 53});
        m.put("dashboard/search_button_hover", new int[]{131, 53});
        m.put("dashboard/search_button_pressed", new int[]{131, 53});
        m.put("dashboard/search_field", new int[]{472, 49});
        m.put("dashboard/spectate_button", new int[]{195, 82});
        m.put("dashboard/spectate_button_disabled", new int[]{195, 82});
        m.put("dashboard/spectate_button_hover", new int[]{195, 82});
        m.put("dashboard/spectate_button_pressed", new int[]{195, 82});
        m.put("dashboard/stat_card", new int[]{186, 116});
        m.put("dashboard/tab_idle", new int[]{343, 72});
        m.put("dashboard/tab_selected", new int[]{348, 72});
        m.put("dashboard/top_rank_row", new int[]{1205, 89});
        m.put("dashboard/win_badge", new int[]{102, 28});
        m.put("houses/leaf", new int[]{192, 192});
        m.put("houses/moon", new int[]{192, 192});
        m.put("houses/star", new int[]{192, 192});
        m.put("houses/sun", new int[]{192, 192});
        m.put("hud/action_frame", new int[]{112, 116});
        m.put("hud/arrow_bludger", new int[]{38, 43});
        m.put("hud/arrow_quaffle", new int[]{36, 37});
        m.put("hud/arrow_snitch", new int[]{37, 30});
        m.put("hud/feed_background", new int[]{280, 69});
        m.put("hud/feed_goal", new int[]{277, 67});
        m.put("hud/feed_kill", new int[]{277, 67});
        m.put("hud/indicator_bludger", new int[]{76, 77});
        m.put("hud/indicator_quaffle", new int[]{70, 72});
        m.put("hud/indicator_snitch", new int[]{77, 70});
        m.put("hud/meter_ring", new int[]{60, 60});
        m.put("hud/quaffle_meter_full", new int[]{60, 60});
        m.put("hud/quaffle_timer_frame", new int[]{113, 116});
        m.put("hud/scoreboard", new int[]{646, 84});
        m.put("hud/snitch_label", new int[]{80, 38});
        m.put("hud/snitch_locked", new int[]{139, 47});
        m.put("hud/timer_plate", new int[]{171, 74});
        m.put("keys/key_bludger_deflect", new int[]{256, 256});
        m.put("keys/key_inactive", new int[]{256, 256});
        m.put("keys/key_quaffle_catch", new int[]{256, 256});
        m.put("keys/key_quaffle_throw", new int[]{256, 256});
        m.put("keys/key_snitch_catch", new int[]{256, 256});
        m.put("matching/accept", new int[]{446, 108});
        m.put("matching/accept_disabled", new int[]{446, 108});
        m.put("matching/accept_hover", new int[]{446, 108});
        m.put("matching/accept_pressed", new int[]{446, 108});
        m.put("matching/decline", new int[]{448, 108});
        m.put("matching/decline_disabled", new int[]{448, 108});
        m.put("matching/decline_hover", new int[]{448, 108});
        m.put("matching/decline_pressed", new int[]{448, 108});
        m.put("matching/panel", new int[]{1044, 586});
        m.put("matching/readiness_empty", new int[]{69, 68});
        m.put("prepare/countdown", new int[]{265, 103});
        m.put("prepare/header", new int[]{517, 187});
        m.put("prepare/member_row", new int[]{563, 117});
        m.put("prepare/ready_button", new int[]{428, 117});
        m.put("prepare/ready_button_disabled", new int[]{428, 117});
        m.put("prepare/ready_button_hover", new int[]{428, 117});
        m.put("prepare/ready_button_pressed", new int[]{428, 117});
        m.put("prepare/spell_panel", new int[]{404, 449});
        m.put("prepare/spell_slot", new int[]{109, 112});
        m.put("prepare/spell_slot_selected", new int[]{109, 112});
        m.put("prepare/team_left", new int[]{619, 529});
        m.put("prepare/team_right", new int[]{620, 530});
        m.put("result/banner_blue", new int[]{106, 120});
        m.put("result/banner_red", new int[]{106, 120});
        m.put("result/confirm_button", new int[]{329, 73});
        m.put("result/confirm_button_disabled", new int[]{329, 73});
        m.put("result/confirm_button_hover", new int[]{329, 73});
        m.put("result/confirm_button_pressed", new int[]{329, 73});
        m.put("result/main", new int[]{1219, 878});
        m.put("result/player_row", new int[]{547, 77});
        m.put("result/portrait_frame", new int[]{75, 76});
        m.put("result/team_a", new int[]{575, 358});
        m.put("result/team_b", new int[]{575, 358});
        m.put("skills/catalog_slot", new int[]{136, 123});
        m.put("skills/catalog_slot_selected", new int[]{136, 123});
        m.put("skills/close", new int[]{47, 45});
        m.put("skills/confirm", new int[]{578, 105});
        m.put("skills/confirm_disabled", new int[]{578, 105});
        m.put("skills/confirm_hover", new int[]{578, 105});
        m.put("skills/confirm_pressed", new int[]{578, 105});
        m.put("skills/equipped_slot", new int[]{151, 152});
        m.put("skills/equipped_slot_selected", new int[]{151, 152});
        m.put("skills/panel", new int[]{1298, 951});
        m.put("skills/tooltip_background", new int[]{186, 116});
        m.put("statistics/bludger", new int[]{125, 126});
        m.put("statistics/damage", new int[]{120, 124});
        m.put("statistics/death", new int[]{106, 126});
        m.put("statistics/goal", new int[]{110, 134});
        m.put("statistics/heal", new int[]{120, 119});
        m.put("statistics/kill", new int[]{121, 122});
        m.put("statistics/mvp", new int[]{122, 134});
        m.put("statistics/snitch", new int[]{126, 135});
        m.put("status/ready_check", new int[]{48, 48});
        m.put("status/setting_hourglass", new int[]{48, 55});
        m.put("tiers/bronze", new int[]{1254, 1254});
        m.put("tiers/challenger_1", new int[]{1254, 1254});
        m.put("tiers/challenger_2", new int[]{1254, 1254});
        m.put("tiers/challenger_3", new int[]{1254, 1254});
        m.put("tiers/diamond", new int[]{1254, 1254});
        m.put("tiers/gold", new int[]{1254, 1254});
        m.put("tiers/master", new int[]{1254, 1254});
        m.put("tiers/platinum", new int[]{1254, 1254});
        m.put("tiers/silver", new int[]{1254, 1254});
        m.put("dashboard/scrollbar_thumb", new int[]{25, 130});   // split from scrollbar.png (thumb rows 4..133)
        m.put("dashboard/scrollbar_track", new int[]{25, 512});   // scrollbar.png with the thumb rows replaced by track
        // owner EXTRA_PNG 2026-10-17: whole windows in canvas coordinates (tabs baked with that tab selected)
        m.put("dashboard/frame_live", new int[]{1672, 941});      // no cards
        m.put("dashboard/frame_ranking", new int[]{1672, 941});   // list panel + scroll track, no rows, no my-rank row (baked thumb cut out)
        m.put("dashboard/rank_thumb", new int[]{21, 124});        // the thumb cut from frame_ranking (x 1431, y 286), background keyed out
        m.put("dashboard/frame_record", new int[]{1672, 941});    // search, profile, 8 stat cards, recent table, 최근 10경기 bar icon baked
        m.put("dashboard/waiting_party_pill", new int[]{700, 104}); // cropped to its alpha box, scaled from 1999
        for (String i : new String[]{"arena", "bar_chart", "cancel", "clock", "crossed_swords", "crown", "hourglass", "party", "range_slider", "search"})
            m.put("icons/" + i, new int[]{128, 128});
        for (int i = 1; i <= 3; i++) m.put("medals/rank_" + i, new int[]{256, 256});
        // owner LINE_BUTTON_PNG 2026-10-17
        for (String v : new String[]{"", "_hover", "_pressed"}) m.put("dashboard/close_button" + v, new int[]{96, 96});
        for (String v : new String[]{"", "_hover", "_pressed", "_disabled"}) m.put("hud/forfeit_button" + v, new int[]{360, 96});
        for (String i : new String[]{"bar_chart_silver", "clock", "crossed_swords_silver", "range_slider"}) m.put("icons_line/" + i, new int[]{128, 128});
        // 결투장 로비 2026-10-18: blurred backgrounds (owner art, Gaussian 5 px at 1672, stored 1280×720), owner MATCH_START_BUTTON, line icons
        m.put("lobby/bg_kyudichi", new int[]{1280, 720});
        m.put("lobby/bg_duel", new int[]{1280, 720});
        for (String v : new String[]{"", "_hover", "_pressed", "_disabled"}) m.put("lobby/match_start" + v, new int[]{640, 160});
        for (String i : new String[]{"pencil", "pin", "people", "search", "chevron_left", "chevron_right", "chevron_up", "chevron_down"}) m.put("lobby/icon_" + i, new int[]{64, 64});
        return Map.copyOf(m);
    }
    public static int[] size(String part) { int[] s = SIZES.get(part); if (s == null) throw new IllegalArgumentException("unknown part " + part); return s; }
    public static boolean exists(String part) { return SIZES.containsKey(part); }
}
