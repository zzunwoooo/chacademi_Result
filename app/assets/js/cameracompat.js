const fs = require('fs-extra')
const path = require('path')
const crypto = require('crypto')
const AdmZip = require('adm-zip')

// Feed-managed Fabric mods are selected explicitly by --fabric.addMods. Only
// a manually installed instance copy can survive removal from the feed.
exports.prepare = function(gameDir){
    const root = path.resolve(gameDir), mods = path.join(root, 'mods')
    const plain = target => {
        const stat = fs.lstatSync(target)
        if(stat.isSymbolicLink() || stat.isFile() && stat.nlink > 1){ throw new Error('Linked camera compatibility path is prohibited') }
        return stat
    }
    plain(root)
    if(!fs.existsSync(mods)){ return { backedUp: 0 } }
    plain(mods)
    const candidates = []
    for(const name of fs.readdirSync(mods)){
        if(!name.toLowerCase().endsWith('.jar')){ continue }
        const file = path.join(mods, name)
        if(!plain(file).isFile()){ continue }
        let metadata
        try{
            const zip = new AdmZip(file), entry = zip.getEntry('fabric.mod.json')
            if(entry){ metadata = JSON.parse(zip.readAsText(entry)) }
        }catch(_err){
            if(/shouldersurfing/i.test(name)){ throw new Error('Shoulder Surfing 파일을 확인할 수 없습니다. 게임을 종료하고 해당 모드를 확인해 주세요.') }
            continue
        }
        if(metadata?.id === 'shouldersurfing'){ candidates.push(file) }
    }
    if(!candidates.length){ return { backedUp: 0 } }
    const backup = path.join(root, 'camera-mod-backups')
    if(fs.existsSync(backup)){ plain(backup) }else{ fs.mkdirSync(backup) }
    for(const file of candidates){
        const bytes = fs.readFileSync(file), hash = crypto.createHash('sha256').update(bytes).digest('hex')
        const target = path.join(backup, hash + '-' + path.basename(file) + '.disabled')
        if(fs.existsSync(target)){
            plain(target)
            if(crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex') !== hash){ throw new Error('Camera mod backup hash mismatch') }
        }else{
            fs.writeFileSync(target, bytes, { flag: 'wx' })
            if(crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex') !== hash){ throw new Error('Camera mod backup hash mismatch') }
        }
        // A Windows sharing violation stops launch; no process is terminated.
        fs.unlinkSync(file)
    }
    return { backedUp: candidates.length }
}
