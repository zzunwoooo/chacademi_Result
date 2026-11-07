// 운빨깨굴랜드 디스코드 보이스 자동 이동 봇
// 마인크래프트 서버(KkaegulCore)가 포커·섯다 테이블을 정하면, 같은 테이블 사람끼리 테이블별 음성 채널로 옮기고
// 테이블이 끝나면 원래 있던 채널로 돌려보낸 뒤 임시 채널을 지운다.
// 계정 연동: 게임에서 /디코연동 → 디스코드에서 /연동 <코드>
'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');
const { Client, GatewayIntentBits, ChannelType, PermissionFlagsBits, SlashCommandBuilder, MessageFlags } = require('discord.js');

const CFG_PATH = path.join(__dirname, 'config.json');
if (!fs.existsSync(CFG_PATH)) {
  console.error('config.json 이 없어요. config.example.json 을 복사해서 config.json 으로 만들고 값을 채워 주세요.');
  process.exit(1);
}
const cfg = JSON.parse(fs.readFileSync(CFG_PATH, 'utf8'));
const LINKS_PATH = path.join(__dirname, 'links.json');
const STATE_PATH = path.join(__dirname, 'state.json');

// uuid(마크) -> discordId
let links = load(LINKS_PATH, {});
// 진행 중인 임시 채널: key "game:no" -> { channelId, origin: { discordId: channelId } }
let tables = load(STATE_PATH, {});
// 연동 대기 코드: code -> { uuid, name, exp }
const pending = new Map();

function load(p, def) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return def; } }
function save(p, v) { fs.writeFileSync(p, JSON.stringify(v, null, 2)); }
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildVoiceStates] });

async function guild() { return client.guilds.fetch(cfg.guildId); }

// ───────── 슬래시 명령 /연동 ─────────
client.once('ready', async () => {
  log(`로그인: ${client.user.tag}`);
  try {
    const g = await guild();
    await g.commands.set([
      new SlashCommandBuilder().setName('연동').setDescription('마인크래프트 계정 연동 (게임에서 /디코연동 으로 받은 코드)')
        .addStringOption(o => o.setName('코드').setDescription('6자리 코드').setRequired(true)),
      new SlashCommandBuilder().setName('연동해제').setDescription('마인크래프트 계정 연동 해제'),
    ].map(c => c.toJSON()));
    log('슬래시 명령 등록 완료');
  } catch (e) { log('슬래시 명령 등록 실패 (봇 초대 시 applications.commands 권한 확인):', e.message); }
  await cleanupOrphans();
  await applyProfile();
});

// ───────── 봇 이름·프로필 사진 (런처 아이콘) ─────────
// avatar.png 가 바뀌었거나 이름(cfg.botName, 기본 "운빨깨굴랜드")이 다를 때만 바꿈. 디스코드 제한: 이름은 1시간에 2번.
async function applyProfile() {
  const want = cfg.botName || '운빨깨굴랜드';
  const av = path.join(__dirname, 'avatar.png');
  const mark = path.join(__dirname, '.profile.json');
  const done = load(mark, {});
  try {
    if (fs.existsSync(av)) {
      const size = fs.statSync(av).size;
      if (done.avatarSize !== size) {
        await client.user.setAvatar(av);
        done.avatarSize = size; save(mark, done);
        log('프로필 사진 적용');
      }
    }
    if (client.user.username !== want) {
      await client.user.setUsername(want);
      log(`봇 이름 변경: ${want}`);
    }
  } catch (e) { log('프로필 변경 실패 (잠시 후 다시 켜면 재시도):', e.message); }
}

client.on('interactionCreate', async (it) => {
  if (!it.isChatInputCommand()) return;
  if (it.commandName === '연동') {
    const code = (it.options.getString('코드') || '').trim();
    const p = pending.get(code);
    if (!p || p.exp < Date.now()) {
      return it.reply({ content: '코드가 없거나 시간이 지났어요. 게임에서 `/디코연동` 을 다시 입력해 주세요.', flags: MessageFlags.Ephemeral });
    }
    pending.delete(code);
    for (const [u, d] of Object.entries(links)) if (d === it.user.id) delete links[u]; // 한 디코 계정 = 한 마크 계정
    links[p.uuid] = it.user.id;
    save(LINKS_PATH, links);
    log(`연동: ${p.name} (${p.uuid}) ↔ ${it.user.tag}`);
    return it.reply({ content: `✅ **${p.name}** 계정과 연동됐어요! 미니게임 테이블이 정해지면 음성 채널로 자동 이동돼요. (음성 채널에 들어가 있을 때)`, flags: MessageFlags.Ephemeral });
  }
  if (it.commandName === '연동해제') {
    let n = 0;
    for (const [u, d] of Object.entries(links)) if (d === it.user.id) { delete links[u]; n++; }
    save(LINKS_PATH, links);
    return it.reply({ content: n ? '연동을 해제했어요.' : '연동된 계정이 없어요.', flags: MessageFlags.Ephemeral });
  }
});

// ───────── 테이블 음성 채널 ─────────
async function openTables(ev) {
  const g = await guild();
  for (const t of ev.tables || []) {
    const key = `${ev.game}:${t.no}`;
    if (tables[key]) await closeTable(key);
    const members = [];
    for (const uuid of t.players || []) {
      const did = links[uuid];
      if (!did) continue;
      const m = await g.members.fetch(did).catch(() => null);
      if (m && m.voice && m.voice.channelId) members.push(m);
    }
    if (members.length === 0) { log(`${key}: 음성 채널에 있는 연동 유저가 없어서 건너뜀`); continue; }
    const name = `${cfg.channelPrefix || '🐸'} ${ev.label || ev.game} ${t.no}번 테이블`;
    const opts = { name, type: ChannelType.GuildVoice, userLimit: 0, reason: '운빨깨굴랜드 미니게임 테이블' };
    if (cfg.categoryId) opts.parent = cfg.categoryId;
    const ch = await g.channels.create(opts).catch(e => { log('채널 만들기 실패 (채널 관리 권한 확인):', e.message); return null; });
    if (!ch) continue;
    tables[key] = { channelId: ch.id, origin: {} };
    for (const m of members) {
      tables[key].origin[m.id] = m.voice.channelId;
      await m.voice.setChannel(ch, '미니게임 테이블 이동').catch(e => log(`이동 실패 ${m.user.tag}: ${e.message} (멤버 이동 권한 확인)`));
    }
    save(STATE_PATH, tables);
    log(`${key}: ${members.length}명 → #${name}`);
  }
}

async function closeTable(key) {
  const t = tables[key];
  if (!t) return;
  delete tables[key];
  save(STATE_PATH, tables);
  const g = await guild();
  const ch = await g.channels.fetch(t.channelId).catch(() => null);
  if (ch) {
    if (cfg.returnToOrigin !== false) {
      for (const [id, m] of ch.members) {
        const back = t.origin[id];
        if (back) await m.voice.setChannel(back, '미니게임 끝 · 원래 채널로').catch(() => {});
      }
    }
    // 남은 사람은 디스코드가 채널 삭제 시 연결을 끊으므로, 아무도 없을 때 지움
    setTimeout(async () => {
      const c = await g.channels.fetch(t.channelId).catch(() => null);
      if (c) await c.delete('미니게임 테이블 끝').catch(() => {});
    }, 1500);
  }
  log(`${key}: 끝 → 원래 채널로`);
}

async function endGame(game) {
  for (const key of Object.keys(tables)) if (key.startsWith(game + ':')) await closeTable(key);
}

/** 봇이 꺼진 사이 남은 임시 채널 정리 */
async function cleanupOrphans() {
  for (const key of Object.keys(tables)) await closeTable(key);
}

// ───────── 마크 서버 → 봇 HTTP ─────────
const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/event') { res.writeHead(404).end(); return; }
  if (!cfg.secret || req.headers['x-kkaegul-secret'] !== cfg.secret) { res.writeHead(403).end(); return; }
  let body = '';
  req.on('data', c => { body += c; if (body.length > 1e6) req.destroy(); });
  req.on('end', async () => {
    res.writeHead(200).end('ok');
    let ev;
    try { ev = JSON.parse(body); } catch { return; }
    try {
      switch (ev.event) {
        case 'link-code':
          pending.set(String(ev.code), { uuid: ev.uuid, name: ev.name, exp: Date.now() + 10 * 60_000 });
          break;
        case 'tables': await openTables(ev); break;
        case 'table-end': await closeTable(`${ev.game}:${ev.no}`); break;
        case 'end': await endGame(ev.game); break;
      }
    } catch (e) { log('처리 오류:', e.message); }
  });
});
server.listen(cfg.port || 8787, '127.0.0.1', () => log(`마크 서버 연결 대기: http://127.0.0.1:${cfg.port || 8787}/event`));

client.login(cfg.token);
