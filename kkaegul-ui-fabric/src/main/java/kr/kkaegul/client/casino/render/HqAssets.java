package kr.kkaegul.client.casino.render;

/**
 * HUD 에셋 목록 (자동 생성).
 * 각 텍스처는 원본 해상도(0번)부터 절반씩 줄인 단계별 이미지를 가진다. 숫자는 각 단계의 [가로, 세로] 픽셀.
 * 원본 PNG: assets/elsea/textures/gui/이름/0.png
 */
public final class HqAssets {

    private HqAssets() {
    }

    public static final HqTexture HUD_BACKGROUND = new HqTexture("hud_background", new int[][]{{2039, 342}, {1020, 171}, {510, 86}, {255, 43}, {127, 21}, {64, 11}, {32, 5}});
    public static final HqTexture MENU_BACKGROUND = new HqTexture("menu_background", new int[][]{{671, 1474}, {336, 737}, {168, 368}, {84, 184}, {42, 92}, {21, 46}});
    public static final HqTexture WALLET = new HqTexture("wallet", new int[][]{{996, 1033}, {498, 516}, {249, 258}, {124, 129}, {62, 65}, {31, 32}});
    public static final HqTexture DEBT = new HqTexture("debt", new int[][]{{940, 992}, {470, 496}, {235, 248}, {118, 124}, {59, 62}});
    public static final HqTexture CHEVRON_DOWN = new HqTexture("chevron_down", new int[][]{{746, 433}, {373, 216}, {186, 108}, {93, 54}, {47, 27}});
    public static final HqTexture CHEVRON_UP = new HqTexture("chevron_up", new int[][]{{800, 465}, {400, 232}, {200, 116}, {100, 58}, {50, 29}});
    public static final HqTexture DIVIDER = new HqTexture("divider", new int[][]{{61, 1082}, {30, 541}, {15, 270}, {8, 135}, {4, 68}, {2, 34}});
    public static final HqTexture MENU_BANK = new HqTexture("menu_bank", new int[][]{{1129, 1052}, {564, 526}, {282, 263}, {141, 132}, {71, 66}, {35, 33}});
    public static final HqTexture MENU_SHOP = new HqTexture("menu_shop", new int[][]{{1078, 1041}, {539, 520}, {270, 260}, {135, 130}, {67, 65}, {34, 33}});
    public static final HqTexture MENU_MAIL = new HqTexture("menu_mail", new int[][]{{980, 1123}, {490, 562}, {245, 281}, {122, 140}, {61, 70}, {31, 35}});
    public static final HqTexture MENU_TRAVEL = new HqTexture("menu_travel", new int[][]{{950, 1120}, {475, 560}, {238, 280}, {119, 140}, {59, 70}, {30, 35}});
    public static final HqTexture MENU_FRIENDS = new HqTexture("menu_friends", new int[][]{{1034, 913}, {517, 456}, {258, 228}, {129, 114}, {65, 57}, {32, 29}});
    public static final HqTexture MENU_RANKING = new HqTexture("menu_ranking", new int[][]{{1143, 1046}, {572, 523}, {286, 262}, {143, 131}, {71, 65}, {36, 33}});
    public static final HqTexture SPOON_DIAMOND = new HqTexture("spoon_diamond", new int[][]{{1127, 1103}, {564, 552}, {282, 276}, {141, 138}, {70, 69}, {35, 34}});
    public static final HqTexture SPOON_GOLD = new HqTexture("spoon_gold", new int[][]{{1119, 1082}, {560, 541}, {280, 270}, {140, 135}, {70, 68}, {35, 34}});
    public static final HqTexture SPOON_IRON = new HqTexture("spoon_iron", new int[][]{{1060, 1077}, {530, 538}, {265, 269}, {132, 135}, {66, 67}, {33, 34}});
    public static final HqTexture SPOON_COPPER = new HqTexture("spoon_copper", new int[][]{{1054, 1048}, {527, 524}, {264, 262}, {132, 131}, {66, 66}, {33, 33}});
    public static final HqTexture SPOON_WOOD = new HqTexture("spoon_wood", new int[][]{{1076, 1116}, {538, 558}, {269, 279}, {134, 140}, {67, 70}, {34, 35}});

    // ----- 은행 -----
    public static final HqTexture BANK_BASE = new HqTexture("bank_base", new int[][]{{1312, 1199}, {656, 600}, {328, 300}, {164, 150}, {82, 75}, {41, 37}});
    public static final HqTexture BANK_DEBT_ROW = new HqTexture("bank_debt_row", new int[][]{{2051, 265}, {1026, 132}, {513, 66}, {256, 33}, {128, 17}, {64, 8}, {32, 4}});
    public static final HqTexture BANK_CREST = new HqTexture("bank_crest", new int[][]{{1700, 688}, {850, 344}, {425, 172}, {212, 86}, {106, 43}, {53, 22}});
    public static final HqTexture BANK_SAVINGS = new HqTexture("bank_savings", new int[][]{{947, 968}, {474, 484}, {237, 242}, {118, 121}, {59, 60}});
    public static final HqTexture BANK_SAVINGS_GRAY = new HqTexture("bank_savings_gray", new int[][]{{947, 968}, {474, 484}, {237, 242}, {118, 121}, {59, 60}});
    public static final HqTexture BANK_LIMIT = new HqTexture("bank_limit", new int[][]{{1067, 714}, {534, 357}, {267, 178}, {133, 89}, {67, 45}, {33, 22}});
    public static final HqTexture BANK_LOCK = new HqTexture("bank_lock", new int[][]{{924, 1023}, {462, 512}, {231, 256}, {116, 128}, {58, 64}, {29, 32}});
    public static final HqTexture BANK_INPUT = new HqTexture("bank_input", new int[][]{{1968, 429}, {984, 214}, {492, 107}, {246, 54}, {123, 27}, {62, 13}});
    public static final HqTexture BANK_BUTTON = new HqTexture("bank_button", new int[][]{{2034, 497}, {1017, 248}, {508, 124}, {254, 62}, {127, 31}, {64, 16}, {32, 8}});
    public static final HqTexture BANK_BUTTON_DISABLED = new HqTexture("bank_button_disabled", new int[][]{{2089, 289}, {1044, 144}, {522, 72}, {261, 36}, {131, 18}, {65, 9}, {33, 5}});
    public static final HqTexture BANK_CLOSE = new HqTexture("bank_close", new int[][]{{886, 864}, {443, 432}, {222, 216}, {111, 108}, {55, 54}});
    public static final HqTexture BANK_BUTTON_REPAY = new HqTexture("bank_button_repay", new int[][]{{2034, 497}, {1017, 248}, {508, 124}, {254, 62}, {127, 31}, {64, 16}, {32, 8}});
    public static final HqTexture BANK_SAVE_ROW = new HqTexture("bank_save_row", new int[][]{{2051, 265}, {1026, 132}, {513, 66}, {256, 33}, {128, 17}, {64, 8}, {32, 4}});
    public static final HqTexture DEBT_GRAY = new HqTexture("debt_gray", new int[][]{{940, 992}, {470, 496}, {235, 248}, {118, 124}, {59, 62}});

    // ----- 상점 -----
    public static final HqTexture SHOP_BASE = new HqTexture("shop_base", new int[][]{{1233, 1276}, {616, 638}, {308, 319}, {154, 160}, {77, 80}, {39, 40}});
    public static final HqTexture TRADE_BASE = new HqTexture("trade_base", new int[][]{{1122, 1402}, {561, 701}, {280, 350}, {140, 175}, {70, 88}, {35, 44}});
    public static final HqTexture SHOP_BANNER_MINT = new HqTexture("shop_banner_mint", new int[][]{{2126, 388}, {1063, 194}, {532, 97}, {266, 48}, {133, 24}, {66, 12}, {33, 6}});
    public static final HqTexture SHOP_BANNER_PEACH = new HqTexture("shop_banner_peach", new int[][]{{2099, 380}, {1050, 190}, {525, 95}, {262, 48}, {131, 24}, {66, 12}, {33, 6}});
    public static final HqTexture SHOP_BANNER_MILEAGE = new HqTexture("shop_banner_mileage", new int[][]{{2155, 409}, {1078, 204}, {539, 102}, {269, 51}, {135, 26}, {67, 13}, {34, 6}});
    public static final HqTexture AWNING_MINT = new HqTexture("awning_mint", new int[][]{{1951, 593}, {976, 296}, {488, 148}, {244, 74}, {122, 37}, {61, 19}});
    public static final HqTexture AWNING_PEACH = new HqTexture("awning_peach", new int[][]{{1983, 610}, {992, 305}, {496, 152}, {248, 76}, {124, 38}, {62, 19}});
    public static final HqTexture AWNING_MILEAGE = new HqTexture("awning_mileage", new int[][]{{1876, 592}, {938, 296}, {469, 148}, {234, 74}, {117, 37}, {59, 18}});
    public static final HqTexture BTN_NEUTRAL = new HqTexture("btn_neutral", new int[][]{{2045, 588}, {1022, 294}, {511, 147}, {256, 74}, {128, 37}, {64, 18}, {32, 9}});
    public static final HqTexture BTN_MINT = new HqTexture("btn_mint", new int[][]{{1982, 527}, {991, 264}, {496, 132}, {248, 66}, {124, 33}, {62, 16}});
    public static final HqTexture BTN_PEACH = new HqTexture("btn_peach", new int[][]{{2011, 528}, {1006, 264}, {503, 132}, {251, 66}, {126, 33}, {63, 16}});
    public static final HqTexture BTN_MILEAGE = new HqTexture("btn_mileage", new int[][]{{2050, 520}, {1025, 260}, {512, 130}, {256, 65}, {128, 32}, {64, 16}, {32, 8}});
    public static final HqTexture SHOP_INPUT = new HqTexture("shop_input", new int[][]{{2062, 430}, {1031, 215}, {516, 108}, {258, 54}, {129, 27}, {64, 13}, {32, 7}});
    public static final HqTexture SCROLL_THUMB = new HqTexture("scroll_thumb", new int[][]{{61, 1082}, {30, 541}, {15, 270}, {8, 135}, {4, 68}, {2, 34}});
    public static final HqTexture ICON_MILEAGE = new HqTexture("icon_mileage", new int[][]{{1111, 1108}, {556, 554}, {278, 277}, {139, 138}, {69, 69}, {35, 35}});
    public static final HqTexture ICON_NOTICE = new HqTexture("icon_notice", new int[][]{{1178, 1171}, {589, 586}, {294, 293}, {147, 146}, {74, 73}, {37, 37}});
    public static final HqTexture ICON_PRICE_TAG = new HqTexture("icon_price_tag", new int[][]{{1102, 997}, {551, 498}, {276, 249}, {138, 125}, {69, 62}, {34, 31}});
    public static final HqTexture ICON_SEARCH = new HqTexture("icon_search", new int[][]{{1044, 1097}, {522, 548}, {261, 274}, {130, 137}, {65, 69}, {33, 34}});
    public static final HqTexture ICON_SELLABLE = new HqTexture("icon_sellable", new int[][]{{1064, 1038}, {532, 519}, {266, 260}, {133, 130}, {66, 65}, {33, 32}});

    // ----- 우편함 -----
    public static final HqTexture MAIL_BUTTON_MINT = new HqTexture("mail_button_mint", new int[][]{{1982, 527}, {991, 264}, {496, 132}, {248, 66}, {124, 33}, {62, 16}});
    public static final HqTexture MAIL_BUTTON_NEUTRAL = new HqTexture("mail_button_neutral", new int[][]{{2045, 588}, {1022, 294}, {511, 147}, {256, 74}, {128, 37}, {64, 18}, {32, 9}});
    public static final HqTexture MAIL_BUTTON_PEACH = new HqTexture("mail_button_peach", new int[][]{{2011, 528}, {1006, 264}, {503, 132}, {251, 66}, {126, 33}, {63, 16}});
    public static final HqTexture MAIL_CHECKBOX_CHECKED = new HqTexture("mail_checkbox_checked", new int[][]{{722, 715}, {361, 358}, {180, 179}, {90, 89}, {45, 45}});
    public static final HqTexture MAIL_CHECKBOX_EMPTY = new HqTexture("mail_checkbox_empty", new int[][]{{638, 621}, {319, 310}, {160, 155}, {80, 78}, {40, 39}});
    public static final HqTexture MAIL_CLOSE_BUTTON = new HqTexture("mail_close_button", new int[][]{{886, 864}, {443, 432}, {222, 216}, {111, 108}, {55, 54}});
    public static final HqTexture MAIL_SCROLL_THUMB = new HqTexture("mail_scroll_thumb", new int[][]{{61, 1082}, {30, 541}, {15, 270}, {8, 135}, {4, 68}, {2, 34}});
    public static final HqTexture MAIL_ATTACHMENT = new HqTexture("mail_attachment", new int[][]{{1067, 1037}, {534, 518}, {267, 259}, {133, 130}, {67, 65}, {33, 32}});
    public static final HqTexture MAIL_DELETE = new HqTexture("mail_delete", new int[][]{{972, 1097}, {486, 548}, {243, 274}, {122, 137}, {61, 69}, {30, 34}});
    public static final HqTexture MAIL_ENVELOPE_READ = new HqTexture("mail_envelope_read", new int[][]{{1135, 1140}, {568, 570}, {284, 285}, {142, 142}, {71, 71}, {35, 36}});
    public static final HqTexture MAIL_ENVELOPE_UNREAD = new HqTexture("mail_envelope_unread", new int[][]{{1029, 734}, {514, 367}, {257, 184}, {129, 92}, {64, 46}, {32, 23}});
    public static final HqTexture MAIL_PARTIAL = new HqTexture("mail_partial", new int[][]{{1063, 1062}, {532, 531}, {266, 266}, {133, 133}, {66, 66}, {33, 33}});
    public static final HqTexture MAIL_RECEIVE = new HqTexture("mail_receive", new int[][]{{1175, 1076}, {588, 538}, {294, 269}, {147, 134}, {73, 67}, {37, 34}});
    public static final HqTexture MAIL_REWARD_ITEM = new HqTexture("mail_reward_item", new int[][]{{1019, 1060}, {510, 530}, {255, 265}, {127, 132}, {64, 66}, {32, 33}});
    public static final HqTexture MAIL_REWARD_MONEY = new HqTexture("mail_reward_money", new int[][]{{1013, 845}, {506, 422}, {253, 211}, {127, 106}, {63, 53}});
    public static final HqTexture MAIL_SUCCESS = new HqTexture("mail_success", new int[][]{{1018, 1017}, {509, 508}, {254, 254}, {127, 127}, {64, 64}, {32, 32}});
    public static final HqTexture MAIL_WALLET = new HqTexture("mail_wallet", new int[][]{{996, 1033}, {498, 516}, {249, 258}, {124, 129}, {62, 65}, {31, 32}});
    public static final HqTexture MAIL_MAILBOX_TITLE = new HqTexture("mail_mailbox_title", new int[][]{{1912, 671}, {956, 336}, {478, 168}, {239, 84}, {120, 42}, {60, 21}});
    public static final HqTexture MAIL_POPUP_ENVELOPE = new HqTexture("mail_popup_envelope", new int[][]{{1613, 540}, {806, 270}, {403, 135}, {202, 68}, {101, 34}, {50, 17}});
    public static final HqTexture MAIL_ATTACHMENT_SLOT = new HqTexture("mail_attachment_slot", new int[][]{{802, 769}, {401, 384}, {200, 192}, {100, 96}, {50, 48}});
    public static final HqTexture MAIL_BADGE_MINT = new HqTexture("mail_badge_mint", new int[][]{{1489, 473}, {744, 236}, {372, 118}, {186, 59}, {93, 30}, {47, 15}});
    public static final HqTexture MAIL_BADGE_NEUTRAL = new HqTexture("mail_badge_neutral", new int[][]{{2045, 588}, {1022, 294}, {511, 147}, {256, 74}, {128, 37}, {64, 18}, {32, 9}});
    public static final HqTexture MAIL_BADGE_PEACH = new HqTexture("mail_badge_peach", new int[][]{{1415, 488}, {708, 244}, {354, 122}, {177, 61}, {88, 30}, {44, 15}});
    public static final HqTexture MAIL_BANNER_MINT = new HqTexture("mail_banner_mint", new int[][]{{2126, 388}, {1063, 194}, {532, 97}, {266, 48}, {133, 24}, {66, 12}, {33, 6}});
    public static final HqTexture MAIL_BANNER_PEACH = new HqTexture("mail_banner_peach", new int[][]{{2099, 380}, {1050, 190}, {525, 95}, {262, 48}, {131, 24}, {66, 12}, {33, 6}});
    public static final HqTexture MAIL_MAIL_ROW_SELECTED = new HqTexture("mail_mail_row_selected", new int[][]{{2055, 350}, {1028, 175}, {514, 88}, {257, 44}, {128, 22}, {64, 11}, {32, 5}});
    public static final HqTexture MAIL_MAIL_ROW = new HqTexture("mail_mail_row", new int[][]{{2122, 361}, {1061, 180}, {530, 90}, {265, 45}, {133, 23}, {66, 11}, {33, 6}});
    public static final HqTexture MAIL_MAILBOX_BASE = new HqTexture("mail_mailbox_base", new int[][]{{1430, 1100}, {715, 550}, {358, 275}, {179, 138}, {89, 69}, {45, 34}});
    /** 팝업 배경 (투명 여백을 잘라낸 것) */
    public static final HqTexture MAIL_POPUP_BOX = new HqTexture("mail_popup_box", new int[][]{{1310, 944}, {655, 472}, {328, 236}, {164, 118}, {82, 59}, {41, 30}});
    public static final HqTexture MAIL_POPUP_BASE = new HqTexture("mail_popup_base", new int[][]{{1344, 1170}, {672, 585}, {336, 292}, {168, 146}, {84, 73}, {42, 37}});
    public static final HqTexture MAIL_BUTTON_GREEN = new HqTexture("mail_button_green", new int[][]{{1982, 527}, {991, 264}, {496, 132}, {248, 66}, {124, 33}, {62, 16}});

    /** 상점 전용 아이콘 */
    public static final HqTexture SHOP_INVITE_TICKET = new HqTexture("shop_invite_ticket", new int[][]{{512, 353}, {256, 176}, {128, 88}, {64, 44}, {32, 22}});

    /** "elsea:..." 아이콘 id → 텍스처 (바닐라 아이템이 아닌 것) */
    public static HqTexture custom(String id) {
        if (id == null) return null;
        return switch (id) {
            case "elsea:invite_ticket" -> SHOP_INVITE_TICKET;
            default -> null;
        };
    }

    public static HqTexture spoon(String id) {
        if (id == null) return null;
        return switch (id) {
            case "diamond" -> SPOON_DIAMOND;
            case "gold" -> SPOON_GOLD;
            case "iron" -> SPOON_IRON;
            case "copper" -> SPOON_COPPER;
            case "wood" -> SPOON_WOOD;
            default -> null;
        };
    }

    // ----- 친구창 · 우편 보내기 (friends-v1) -----
    public static final HqTexture FR_BUTTON_MINT = new HqTexture("fr_button_mint", new int[][]{{1024, 268}, {512, 134}, {256, 67}, {128, 34}, {64, 17}, {32, 8}});
    /** 보내기 버튼 (민트 버튼을 진하게) */
    public static final HqTexture FR_BUTTON_SEND = new HqTexture("fr_button_send", new int[][]{{1024, 268}, {512, 134}, {256, 67}, {128, 34}, {64, 17}, {32, 8}});
    public static final HqTexture FR_BUTTON_NEUTRAL = new HqTexture("fr_button_neutral", new int[][]{{1024, 292}, {512, 146}, {256, 73}, {128, 36}, {64, 18}, {32, 9}});
    public static final HqTexture FR_CLOSE_BUTTON = new HqTexture("fr_close_button", new int[][]{{878, 859}, {439, 430}, {220, 215}, {110, 107}, {55, 54}});
    public static final HqTexture FR_INPUT = new HqTexture("fr_input", new int[][]{{1024, 211}, {512, 106}, {256, 53}, {128, 26}, {64, 13}, {32, 7}});
    public static final HqTexture FR_MESSAGE_INPUT = new HqTexture("fr_message_input", new int[][]{{1024, 335}, {512, 168}, {256, 84}, {128, 42}, {64, 21}, {32, 10}});
    public static final HqTexture FR_SCROLL_THUMB = new HqTexture("fr_scroll_thumb", new int[][]{{50, 1024}, {25, 512}, {12, 256}, {6, 128}, {3, 64}, {2, 32}});
    public static final HqTexture FR_ADD = new HqTexture("fr_add", new int[][]{{512, 508}, {256, 254}, {128, 127}, {64, 64}, {32, 32}});
    public static final HqTexture FR_CHEVRON_DOWN = new HqTexture("fr_chevron_down", new int[][]{{512, 295}, {256, 148}, {128, 74}, {64, 37}, {32, 18}});
    public static final HqTexture FR_CHEVRON_UP = new HqTexture("fr_chevron_up", new int[][]{{512, 296}, {256, 148}, {128, 74}, {64, 37}, {32, 18}});
    public static final HqTexture FR_ENVELOPE = new HqTexture("fr_envelope", new int[][]{{512, 364}, {256, 182}, {128, 91}, {64, 46}, {32, 23}});
    public static final HqTexture FR_ONLINE = new HqTexture("fr_online", new int[][]{{512, 501}, {256, 250}, {128, 125}, {64, 63}, {32, 31}});
    public static final HqTexture FR_REWARD_ITEM = new HqTexture("fr_reward_item", new int[][]{{492, 512}, {246, 256}, {123, 128}, {62, 64}, {31, 32}});
    public static final HqTexture FR_REWARD_MONEY = new HqTexture("fr_reward_money", new int[][]{{512, 428}, {256, 214}, {128, 107}, {64, 54}, {32, 27}});
    public static final HqTexture FR_SEARCH = new HqTexture("fr_search", new int[][]{{487, 512}, {244, 256}, {122, 128}, {61, 64}, {30, 32}});
    public static final HqTexture FR_SEND = new HqTexture("fr_send", new int[][]{{512, 380}, {256, 190}, {128, 95}, {64, 48}, {32, 24}});
    public static final HqTexture FR_WHISPER = new HqTexture("fr_whisper", new int[][]{{512, 435}, {256, 218}, {128, 109}, {64, 54}, {32, 27}});
    public static final HqTexture FR_COMPOSE_ENVELOPE = new HqTexture("fr_compose_envelope", new int[][]{{1024, 339}, {512, 170}, {256, 85}, {128, 42}, {64, 21}, {32, 11}});
    public static final HqTexture FR_FRIENDS_TITLE = new HqTexture("fr_friends_title", new int[][]{{1024, 374}, {512, 187}, {256, 94}, {128, 47}, {64, 23}, {32, 12}});
    public static final HqTexture FR_ATTACHMENT_SLOT = new HqTexture("fr_attachment_slot", new int[][]{{794, 760}, {397, 380}, {198, 190}, {99, 95}, {50, 48}});
    public static final HqTexture FR_FRIENDS_BASE = new HqTexture("fr_friends_base", new int[][]{{667, 1024}, {334, 512}, {167, 256}, {83, 128}, {42, 64}, {21, 32}});
    public static final HqTexture FR_ROW_COPPER = new HqTexture("fr_row_copper", new int[][]{{1024, 174}, {512, 87}, {256, 44}, {128, 22}, {64, 11}, {32, 5}});
    public static final HqTexture FR_ROW_DIAMOND = new HqTexture("fr_row_diamond", new int[][]{{1024, 166}, {512, 83}, {256, 42}, {128, 21}, {64, 10}, {32, 5}});
    public static final HqTexture FR_ROW_GOLD = new HqTexture("fr_row_gold", new int[][]{{1024, 165}, {512, 82}, {256, 41}, {128, 21}, {64, 10}, {32, 5}});
    public static final HqTexture FR_ROW_IRON = new HqTexture("fr_row_iron", new int[][]{{1024, 156}, {512, 78}, {256, 39}, {128, 20}, {64, 10}, {32, 5}});
    public static final HqTexture FR_ROW_WOOD = new HqTexture("fr_row_wood", new int[][]{{1024, 159}, {512, 80}, {256, 40}, {128, 20}, {64, 10}, {32, 5}});
    public static final HqTexture FR_SPOON_COPPER = new HqTexture("fr_spoon_copper", new int[][]{{512, 510}, {256, 255}, {128, 128}, {64, 64}, {32, 32}});
    public static final HqTexture FR_SPOON_DIAMOND = new HqTexture("fr_spoon_diamond", new int[][]{{512, 501}, {256, 250}, {128, 125}, {64, 63}, {32, 31}});
    public static final HqTexture FR_SPOON_GOLD = new HqTexture("fr_spoon_gold", new int[][]{{512, 495}, {256, 248}, {128, 124}, {64, 62}, {32, 31}});
    public static final HqTexture FR_SPOON_IRON = new HqTexture("fr_spoon_iron", new int[][]{{504, 512}, {252, 256}, {126, 128}, {63, 64}, {32, 32}});
    public static final HqTexture FR_SPOON_WOOD = new HqTexture("fr_spoon_wood", new int[][]{{494, 512}, {247, 256}, {124, 128}, {62, 64}, {31, 32}});
}
