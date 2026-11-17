const crypto = require('crypto')
const fs = require('fs-extra')
const path = require('path')
const AdmZip = require('adm-zip')
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const allowed = relative => typeof relative === 'string' && !relative.includes(String.fromCharCode(92)) &&
    !relative.includes(':') && !relative.split('/').some(part => !part || part === '.' || part === '..') &&
    (relative.startsWith('resourcepacks/chacademia-spells/') || relative.startsWith('config/portablevfx/effects/'))

function safeTarget(root, relative){
    let current = root
    for(const part of relative.split('/')){
        current = path.join(current, part)
        if(fs.existsSync(current)){
            const stat = fs.lstatSync(current)
            if(stat.isSymbolicLink() || stat.isFile() && stat.nlink > 1){
                throw new Error('Linked client asset target is prohibited')
            }
        }
    }
    return current
}

exports.install = function(gameDir, module){
    if(!module){ throw new Error('Required client asset bundle is missing') }
    const root = path.resolve(gameDir)
    if(fs.lstatSync(root).isSymbolicLink()){ throw new Error('Linked instance root is prohibited') }
    const artifact = module.artifact
    if(artifact.path !== 'config/magiccodex/launcher/client-assets.zip' || !/^[a-f0-9]{64}$/.test(artifact.SHA256 || '')){
        throw new Error('Invalid client asset archive contract')
    }
    const archive = fs.readFileSync(safeTarget(root, artifact.path))
    if(sha(archive) !== artifact.SHA256){ throw new Error('Client asset archive hash mismatch') }
    const zip = new AdmZip(archive)
    const manifest = JSON.parse(zip.readAsText('manifest.json'))
    if(!Array.isArray(manifest.files) || manifest.files.length === 0 || manifest.files.length > 4096){
        throw new Error('Invalid client asset manifest')
    }
    const names = new Set(['manifest.json'])
    let total = 0
    for(const file of manifest.files){
        if(!allowed(file.path) || !/^[a-f0-9]{64}$/.test(file.sha256) ||
            !Number.isSafeInteger(file.bytes) || file.bytes < 0 || names.has(file.path)){
            throw new Error('Unsafe client asset manifest entry')
        }
        names.add(file.path)
        total += file.bytes
        if(total > 1024 * 1024 * 1024){ throw new Error('Client asset archive exceeds limit') }
        safeTarget(root, file.path)
    }
    const entries = zip.getEntries()
    if(entries.length !== names.size || entries.some(entry => !names.has(entry.entryName))){
        throw new Error('Unexpected client asset archive entry')
    }
    // Verify every member before writing; incomplete installation can be repaired on the next launch.
    for(const file of manifest.files){
        const bytes = zip.readFile(file.path)
        if(!bytes || bytes.length !== file.bytes || sha(bytes) !== file.sha256){
            throw new Error('Client asset member hash mismatch')
        }
    }
    let installed = 0
    for(const file of manifest.files){
        const target = safeTarget(root, file.path)
        if(fs.existsSync(target)){
            if(!fs.statSync(target).isFile()){ throw new Error('Client asset target must be a file') }
            if(fs.statSync(target).size === file.bytes && sha(fs.readFileSync(target)) === file.sha256){ continue }
        }
        fs.ensureDirSync(path.dirname(target))
        const temporary = target + '.chacademi-' + crypto.randomBytes(8).toString('hex') + '.tmp'
        try{
            fs.writeFileSync(temporary, zip.readFile(file.path), { flag: 'wx' })
            fs.moveSync(temporary, target, { overwrite: true })
        }finally{
            if(fs.existsSync(temporary)){ fs.unlinkSync(temporary) }
        }
        installed++
    }
    return { verified: manifest.files.length, installed }
}
