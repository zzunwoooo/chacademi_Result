// 차카데미 실행기 0.1.33 (11/09 오너 "파일 다운로드 오류 HTTP 504 제발 고쳐"):
// helios-core 다운로드는 '응답 없음'만 다시 시도하고 GitHub 가 가끔 주는 502·503·504·429 는 바로 실패 → 파일 수천 개 중 하나만 걸려도 전체 실패.
// node_modules/helios-core/dist/dl/DownloadEngine.js 를 고친다: 5xx·429·408·시간 초과·연결 끊김은 최대 10번, 1·2·4·8·15초 간격으로 다시 받기.
// 빌드 전에 실행: node tools/patch-helios-retry.cjs   (두 번 돌려도 안전)
const fs = require('fs')
const path = require('path')
const file = path.join(__dirname, '..', 'node_modules', 'helios-core', 'dist', 'dl', 'DownloadEngine.js')
let src = fs.readFileSync(file, 'utf8')
if (src.includes('차카데미 0.1.33')) { console.log('already patched'); process.exit(0) }

function swap(from, to) {
    if (!src.includes(from)) throw new Error('pattern not found: ' + from.slice(0, 60))
    src = src.replace(from, to)
}

// 1) 다시 시도할 오류
swap(`function retryableError(error) {
    if (error instanceof got_1.RequestError) {
        // error.name === 'RequestError' means server did not respond.
        return error.name === 'RequestError' || error instanceof got_1.ReadError && error.code === 'ECONNRESET';
    }
    else {
        return false;
    }
}`, `function retryableError(error) {
    // 차카데미 0.1.33: 서버 쪽 일시 오류(5xx·429·408)·시간 초과·연결 끊김은 다시 받는다. 404 같은 진짜 없는 파일은 그대로 실패.
    if (error instanceof got_1.HTTPError) {
        const s = error.response && error.response.statusCode;
        return s === 408 || s === 429 || (s >= 500 && s <= 599);
    }
    if (error instanceof got_1.RequestError) {
        return true;
    }
    const c = error && error.code;
    return c === 'ECONNRESET' || c === 'ETIMEDOUT' || c === 'EPIPE' || c === 'EAI_AGAIN' || c === 'ECONNREFUSED' || c === 'ERR_STREAM_PREMATURE_CLOSE';
}`)

// 2) 기다리는 시간: 1초 고정 → 1·2·4·8·15초 (+0~0.5초)
swap(`await (0, NodeUtil_1.sleep)(1000);`,
     `await (0, NodeUtil_1.sleep)(Math.min(15000, 1000 * Math.pow(2, Math.min(retryCount - 1, 4))) + Math.floor(Math.random() * 500));`)

// 3) 멈춘 연결이 끝없이 기다리지 않게 시간 제한
swap(`const downloadStream = got_1.default.stream(url);`,
     `const downloadStream = got_1.default.stream(url, { timeout: { lookup: 15000, connect: 15000, secureConnect: 15000, response: 60000, socket: 60000 } });`)

fs.writeFileSync(file, src)
console.log('patched ' + file)
