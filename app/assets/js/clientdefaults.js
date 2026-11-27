// 처음 설정을 판(revision)마다 한 번 강제로 맞춤 (사이렌 1.0.2, 2026-11-27)
//  - 쉐이더: 켜기 + 옵션 파일 (replace=true 면 옵션 파일을 통째로 바꿈 = 쉐이더 기본값 + 정책에 적힌 값)
//  - 리소스팩: 정책에 적힌 팩을 켬 (가장 아래 = 다른 팩이 위에서 덮음)
// 정책 파일은 배포 목록의 managed 파일로 내려오고, 같은 판은 다시 적용하지 않음 → 플레이어가 그 뒤에 바꾼 설정은 그대로 둠
const fs = require('fs-extra')
const path = require('path')
const crypto = require('crypto')
const AdmZip = require('adm-zip')
const { merge } = require('./shaderdefaults')

const MODULE_ID = 'config/magiccodex/launcher/client-defaults.json'
const MARKER = 'config/magiccodex/launcher/client-defaults-applied.json'
const FILE_NAME = /^[A-Za-z0-9_.+-]+\.zip$/
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')

function target(root, relative){
    if(typeof relative !== 'string' || relative.includes('\\') || relative.includes(':') ||
        relative.split('/').some(part => !part || part === '.' || part === '..')){
        throw new Error('Unsafe client defaults path')
    }
    let current = root
    for(const part of relative.split('/')){
        current = path.join(current, part)
        if(fs.existsSync(current)){
            const stat = fs.lstatSync(current)
            if(stat.isSymbolicLink() || stat.isFile() && stat.nlink > 1){ throw new Error('Linked client defaults path is prohibited') }
        }
    }
    return current
}

function atomic(file, bytes){
    fs.ensureDirSync(path.dirname(file))
    const temp = file + '.chacademi-' + crypto.randomBytes(8).toString('hex') + '.tmp'
    try{
        fs.writeFileSync(temp, bytes, { flag: 'wx' })
        fs.moveSync(temp, file, { overwrite: true })
    }finally{
        if(fs.existsSync(temp)){ fs.unlinkSync(temp) }
    }
}

function readText(file){ return fs.existsSync(file) ? fs.readFileSync(file).toString('utf8') : null }

// options.txt 의 resourcePacks 에 팩을 가장 아래(첫 file/ 앞)로 넣음. 이미 있으면 그대로
function enablePacks(text, names){
    const original = text || ''
    const lines = original.split(/\r?\n/)
    const at = lines.flatMap((line, i) => line.startsWith('resourcePacks:') ? [i] : [])
    if(at.length > 1){ throw new Error('Duplicate resource pack option') }
    const packs = at.length ? JSON.parse(lines[at[0]].slice('resourcePacks:'.length)) : []
    if(!Array.isArray(packs) || packs.some(p => typeof p !== 'string')){ throw new Error('Invalid resource pack option') }
    let changed = false
    for(const name of names.slice().reverse()){
        const option = 'file/' + name
        if(packs.includes(option)){ continue }
        const first = packs.findIndex(p => p.startsWith('file/'))
        packs.splice(first < 0 ? packs.length : first, 0, option)
        changed = true
    }
    if(!changed){ return original }
    const line = 'resourcePacks:' + JSON.stringify(packs)
    if(at.length){ lines[at[0]] = line }
    else{ if(lines.at(-1) === ''){ lines.pop() } lines.push(line, '') }
    return lines.join(original.includes('\r\n') ? '\r\n' : '\n')
}

exports.MODULE_ID = MODULE_ID
exports.enablePacks = enablePacks
exports.apply = function(gameDir, module){
    if(!module){ return { applied: false, reason: 'no-policy' } }
    const root = path.resolve(gameDir)
    if(module.id !== MODULE_ID || module.updatePolicy !== 'managed' || module.artifact?.path !== MODULE_ID ||
        !/^[a-f0-9]{64}$/.test(module.artifact.SHA256 || '')){
        throw new Error('Invalid client defaults policy module')
    }
    const bytes = fs.readFileSync(target(root, MODULE_ID))
    if(sha(bytes) !== module.artifact.SHA256){ throw new Error('Client defaults policy hash mismatch') }
    const policy = JSON.parse(bytes)
    if(!/^[a-z0-9-]{1,80}$/.test(policy.revision || '')){ throw new Error('Invalid client defaults revision') }
    const marker = target(root, MARKER)
    const state = fs.existsSync(marker) ? fs.readJsonSync(marker) : { applied: {} }
    if(!state.applied || typeof state.applied !== 'object' || Array.isArray(state.applied)){ throw new Error('Invalid client defaults state') }
    if(Object.hasOwn(state.applied, policy.revision)){ return { applied: false, reason: 'already-applied', revision: policy.revision } }

    // 먼저 전부 검사하고 바뀔 내용을 만든 뒤에야 파일을 씀 (중간에 멈춰도 반쯤 바뀐 상태가 안 남게)
    const changes = []
    const s = policy.shader
    if(s){
        if(!FILE_NAME.test(s.pack || '') || !/^[a-f0-9]{64}$/.test(s.packSha256 || '') || typeof s.presetText !== 'string' ||
            sha(Buffer.from(s.presetText, 'utf8')) !== s.presetSha256){
            throw new Error('Invalid shader defaults contract')
        }
        const preset = {}
        for(const line of s.presetText.split(/\r?\n/)){
            if(!line || line.startsWith('#')){ continue }
            const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=([A-Za-z0-9_.-]+)$/)
            if(!match || Object.hasOwn(preset, match[1])){ throw new Error('Invalid shader preset property') }
            preset[match[1]] = match[2]
        }
        const count = Object.keys(preset).length
        if(count < 1 || count > 200){ throw new Error('Unexpected shader preset key count') }
        const pack = fs.readFileSync(target(root, `shaderpacks/${s.pack}`))
        if(sha(pack) !== s.packSha256){ throw new Error('Shader ZIP hash mismatch') }
        if(!new AdmZip(pack).getEntries().some(entry => entry.entryName.startsWith('shaders/'))){ throw new Error('Shader ZIP is incomplete') }
        const presetFile = target(root, `shaderpacks/${s.pack}.txt`)
        const presetBefore = readText(presetFile)
        changes.push({ relative: `shaderpacks/${s.pack}.txt`, file: presetFile, before: presetBefore,
            after: s.replace === true || presetBefore === null ? s.presetText : merge(presetBefore, preset) })
        const irisFile = target(root, 'config/iris.properties'), irisBefore = readText(irisFile)
        changes.push({ relative: 'config/iris.properties', file: irisFile, before: irisBefore,
            after: merge(irisBefore || '', { enableShaders: 'true', shaderPack: s.pack }) })
    }
    const packs = Array.isArray(policy.resourcePacks) ? policy.resourcePacks : []
    if(packs.length){
        for(const p of packs){
            if(!FILE_NAME.test(p?.file || '') || !/^[a-f0-9]{64}$/.test(p.sha256 || '')){ throw new Error('Invalid resource pack defaults') }
            if(sha(fs.readFileSync(target(root, `resourcepacks/${p.file}`))) !== p.sha256){ throw new Error('Resource pack hash mismatch: ' + p.file) }
        }
        const optionsFile = target(root, 'options.txt'), optionsBefore = readText(optionsFile)
        changes.push({ relative: 'options.txt', file: optionsFile, before: optionsBefore, after: enablePacks(optionsBefore, packs.map(p => p.file)) })
    }

    for(const c of changes){
        if(fs.existsSync(c.file) && !fs.statSync(c.file).isFile()){ throw new Error('Client defaults target must be a file') }
        const backup = target(root, `config/magiccodex/launcher/client-defaults-backups/${policy.revision}/${c.relative}.before`)
        if(c.before !== null && !fs.existsSync(backup)){ atomic(backup, Buffer.from(c.before, 'utf8')) }
    }
    for(const c of changes){ if(c.after !== c.before){ atomic(c.file, Buffer.from(c.after, 'utf8')) } }
    state.applied[policy.revision] = { at: new Date().toISOString(), shader: s ? s.pack : null, resourcePacks: packs.map(p => p.file) }
    atomic(marker, JSON.stringify(state, null, 2) + '\n')
    return { applied: true, revision: policy.revision, changed: changes.filter(c => c.after !== c.before).map(c => c.relative) }
}
