const fs = require('fs-extra')
const path = require('path')
const crypto = require('crypto')
const AdmZip = require('adm-zip')

const MODULE_ID = 'config/magiccodex/launcher/shader-defaults-20261020.json'
const PACK = 'ComplementaryUnbound_r5.5.1.zip'
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')

function target(root, relative){
    if(typeof relative !== 'string' || relative.includes('\\') || relative.includes(':') ||
        relative.split('/').some(part => !part || part === '.' || part === '..')){
        throw new Error('Unsafe shader defaults path')
    }
    let current = root
    for(const part of relative.split('/')){
        current = path.join(current, part)
        if(fs.existsSync(current)){
            const stat = fs.lstatSync(current)
            if(stat.isSymbolicLink() || stat.isFile() && stat.nlink > 1){
                throw new Error('Linked shader defaults path is prohibited')
            }
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

// Preserve comments, unrelated properties, and existing line endings byte-for-byte.
function merge(text, values){
    const pending = new Set(Object.keys(values))
    const ending = text.match(/\r*\n/)?.[0] || '\n'
    const chunks = text.match(/[^\n]*\n|[^\n]+$/g) || []
    let result = chunks.map(chunk => {
        const eol = chunk.match(/\r*\n$/)?.[0] || ''
        const body = eol ? chunk.slice(0, -eol.length) : chunk
        const match = body.match(/^\s*([^#!\s=:]+)\s*[=:]/)
        if(!match || !Object.hasOwn(values, match[1])){ return chunk }
        pending.delete(match[1])
        return `${match[1]}=${values[match[1]]}${eol}`
    }).join('')
    if(pending.size && result && !result.endsWith('\n')){ result += ending }
    for(const key of pending){ result += `${key}=${values[key]}${ending}` }
    return result
}

exports.MODULE_ID = MODULE_ID
exports.merge = merge
exports.apply = function(gameDir, module){
    if(!module){ return { applied: false, reason: 'no-policy' } }
    const root = path.resolve(gameDir)
    if(fs.lstatSync(root).isSymbolicLink()){ throw new Error('Linked instance root is prohibited') }
    if(module.id !== MODULE_ID || module.updatePolicy !== 'managed' || module.artifact?.path !== MODULE_ID ||
        !/^[a-f0-9]{64}$/.test(module.artifact.SHA256 || '')){
        throw new Error('Invalid shader defaults policy module')
    }
    const bytes = fs.readFileSync(target(root, MODULE_ID))
    if(sha(bytes) !== module.artifact.SHA256){ throw new Error('Shader defaults policy hash mismatch') }
    const policy = JSON.parse(bytes)
    if(!/^[a-z0-9-]{1,80}$/.test(policy.revision || '') || policy.pack !== PACK ||
        policy.iris?.shaderPack !== PACK || policy.iris?.enableShaders !== 'true' ||
        Object.keys(policy.iris).sort().join(',') !== 'enableShaders,shaderPack' ||
        typeof policy.presetText !== 'string' || sha(Buffer.from(policy.presetText, 'utf8')) !== policy.presetSha256 ||
        !/^[a-f0-9]{64}$/.test(policy.packSha256 || '')){
        throw new Error('Invalid shader defaults contract')
    }
    const preset = {}
    for(const line of policy.presetText.split(/\r?\n/)){
        if(!line || line.startsWith('#')){ continue }
        const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=([A-Za-z0-9_.-]+)$/)
        if(!match || Object.hasOwn(preset, match[1])){ throw new Error('Invalid shader preset property') }
        preset[match[1]] = match[2]
    }
    if(Object.keys(preset).length !== 12){ throw new Error('Unexpected shader preset key count') }
    const marker = target(root, 'config/magiccodex/launcher/shader-defaults-applied.json')
    const state = fs.existsSync(marker) ? fs.readJsonSync(marker) : { applied: {} }
    if(!state.applied || typeof state.applied !== 'object' || Array.isArray(state.applied)){
        throw new Error('Invalid shader defaults state')
    }
    if(Object.hasOwn(state.applied, policy.revision)){ return { applied: false, reason: 'already-applied' } }

    // Do not change selection until the exact existing approved ZIP is installed and valid.
    const pack = fs.readFileSync(target(root, `shaderpacks/${PACK}`))
    if(sha(pack) !== policy.packSha256){ throw new Error('Shader ZIP hash mismatch') }
    const archive = new AdmZip(pack)
    if(!archive.getEntry('License.txt') || !archive.getEntries().some(entry => entry.entryName.startsWith('shaders/'))){
        throw new Error('Shader ZIP is incomplete')
    }
    const changes = [
        { relative: `shaderpacks/${PACK}.txt`, values: preset, initial: policy.presetText },
        { relative: 'config/iris.properties', values: policy.iris, initial: null }
    ].map(change => {
        const file = target(root, change.relative)
        if(fs.existsSync(file) && !fs.statSync(file).isFile()){ throw new Error('Shader configuration must be a file') }
        const before = fs.existsSync(file) ? fs.readFileSync(file) : null
        const after = before === null && change.initial !== null ? change.initial : merge(before?.toString('utf8') || '', change.values)
        return { ...change, file, before, after: Buffer.from(after, 'utf8') }
    })
    // Backups are written once and never replaced by a retry after partial I/O failure.
    for(const change of changes){
        const backup = target(root, `config/magiccodex/launcher/shader-defaults-backups/${policy.revision}/${change.relative}.before`)
        if(change.before !== null && !fs.existsSync(backup)){ atomic(backup, change.before) }
    }
    for(const change of changes){ atomic(change.file, change.after) }
    state.applied[policy.revision] = { presetSha256: policy.presetSha256, packSha256: policy.packSha256 }
    atomic(marker, JSON.stringify(state, null, 2) + '\n')
    return { applied: true, revision: policy.revision, shaderPack: PACK, enabled: true }
}
