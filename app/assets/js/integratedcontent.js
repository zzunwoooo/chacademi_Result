const fs = require('fs-extra')
const path = require('path')
const crypto = require('crypto')
const retirement = require('./story-retirement.json').retired
const PACK = 'resourcepacks/chacademia-integrated-20261023.zip'
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
function target(root, relative){
    if(relative.includes('\\') || relative.includes(':') || relative.split('/').some(p => !p || p === '.' || p === '..')){ throw new Error('Unsafe integrated content path') }
    let current = root
    for(const p of ['', ...relative.split('/')]){
        current = path.join(current, p)
        if(fs.existsSync(current)){
            const stat = fs.lstatSync(current)
            if(stat.isSymbolicLink() || stat.isFile() && stat.nlink > 1){ throw new Error('Linked integrated content is prohibited') }
        }
    }
    return current
}
exports.prepare = function(gameDir, modules){
    const root = path.resolve(gameDir)
    if(!Array.isArray(modules)){ throw new Error('Missing integrated pack contract') }
    const matches = modules.filter(m => m && m.id === path.basename(PACK))
    if(matches.length !== 1){ throw new Error('Missing integrated pack contract') }
    const module = matches[0], artifact = module.artifact
    // The selected distribution declares the version; the install path stays fixed.
    if(module.name !== PACK || !artifact || artifact.path !== PACK ||
        typeof artifact.SHA256 !== 'string' || !/^[a-f0-9]{64}$/.test(artifact.SHA256) ||
        !Number.isSafeInteger(artifact.size) || artifact.size <= 0){
        throw new Error('Missing integrated pack contract')
    }
    const bytes = fs.readFileSync(target(root, PACK))
    if(bytes.length !== artifact.size || sha(bytes) !== artifact.SHA256){ throw new Error('Integrated pack hash mismatch') }
    // Optional NPC pack contract: verify before touching selections or user files.
    const npcPath = 'resourcepacks/chacademia_npc.zip'
    const npcMatches = modules.filter(m => m && m.id === 'chacademia_npc.zip')
    if(npcMatches.length > 1){ throw new Error('NPC 리소스팩 배포 정보가 중복되었습니다') }
    const npc = npcMatches[0]
    if(npc){
        const a = npc.artifact
        if(npc.name !== npcPath || !a || a.path !== npcPath ||
            typeof a.SHA256 !== 'string' || !/^[a-f0-9]{64}$/.test(a.SHA256) ||
            !Number.isSafeInteger(a.size) || a.size <= 0){ throw new Error('NPC 리소스팩 배포 정보가 올바르지 않습니다') }
        const content = fs.readFileSync(target(root, npcPath))
        if(content.length !== a.size || sha(content) !== a.SHA256){ throw new Error('NPC 리소스팩 파일 검증에 실패했습니다') }
    }
    const optionFile = target(root, 'options.txt')
    const original = fs.existsSync(optionFile) ? fs.readFileSync(optionFile, 'utf8') : ''
    const lines = original.split(/\r?\n/)
    const indices = lines.flatMap((line,i) => line.startsWith('resourcePacks:') ? [i] : [])
    if(indices.length > 1){ throw new Error('Duplicate resource pack option') }
    const packs = indices.length ? JSON.parse(lines[indices[0]].slice('resourcePacks:'.length)) : []
    if(!Array.isArray(packs) || packs.some(p => typeof p !== 'string')){ throw new Error('Invalid resource pack option') }
    const planned = []
    for(const item of retirement){
        if(!/^config\/chaca_dialogue\/ch1-[a-z0-9-]+\/(?:dialogue\.yml|[a-z0-9_-]+\.png)$/.test(item.path) || !/^[a-f0-9]{64}$/.test(item.sha256)){ throw new Error('Unsafe retirement entry') }
        const file = target(root,item.path)
        if(fs.existsSync(file) && fs.lstatSync(file).isFile() && sha(fs.readFileSync(file)) === item.sha256 && !planned.some(p => p.path === item.path)){ planned.push(item) }
    }
    // Journal first; every moved byte remains recoverable at its original relative path.
    let quarantine = null
    if(planned.length){
        const relative = 'config/magiccodex/launcher/quarantine/' + crypto.randomUUID()
        quarantine = target(root,relative)
        fs.ensureDirSync(quarantine)
        fs.writeFileSync(path.join(quarantine,'journal.json'), JSON.stringify({source:'story-retired-managed-files',files:planned},null,2), {flag:'wx'})
        for(const item of planned){
            const from=target(root,item.path), to=target(root,relative+'/'+item.path)
            // Recheck immediately before moving. A changed user file is retained.
            if(!fs.existsSync(from) || sha(fs.readFileSync(from)) !== item.sha256){ continue }
            fs.ensureDirSync(path.dirname(to)); fs.moveSync(from,to,{overwrite:false})
        }
    }
    const option = 'file/' + path.basename(PACK)
    const originalPacks = JSON.stringify(packs)
    if(!packs.includes(option)){ packs.push(option) }
    if(npc){
        const npcOption = 'file/chacademia_npc.zip'
        // Deactivate only the previous same-name folder; retain its bytes for recovery.
        let seenNpc = false
        const selected = packs.filter(pack => {
            if(pack === 'file/chacademia_npc' || pack === 'file/chacademia_npc/'){ return false }
            if(pack !== npcOption){ return true }
            if(seenNpc){ return false }
            seenNpc = true; return true
        })
        if(!seenNpc){ selected.push(npcOption) }
        packs.splice(0, packs.length, ...selected)
    }
    // Misa texture pack: default on at the lowest priority (first file/ entry) when the distribution ships it.
    const misaPath = 'resourcepacks/Misa_1.21L.zip', misaOption = 'file/Misa_1.21L.zip'
    if(modules.some(m => m && m.id === 'Misa_1.21L.zip') && fs.existsSync(target(root, misaPath)) && !packs.includes(misaOption)){
        const at = packs.findIndex(p => p.startsWith('file/'))
        packs.splice(at < 0 ? packs.length : at, 0, misaOption)
    }
    if(JSON.stringify(packs) !== originalPacks){
        if(indices.length){ lines[indices[0]]='resourcePacks:'+JSON.stringify(packs) }
        else{ if(lines.at(-1)===''){lines.pop()} lines.push('resourcePacks:'+JSON.stringify(packs),'') }
        const id=crypto.randomUUID(), temporary=optionFile+'.'+id+'.tmp'
        if(fs.existsSync(optionFile)){ fs.copyFileSync(optionFile,optionFile+'.chacademi-'+id+'.bak',fs.constants.COPYFILE_EXCL) }
        try{ fs.writeFileSync(temporary,lines.join(original.includes('\r\n')?'\r\n':'\n'),{flag:'wx'}); fs.renameSync(temporary,optionFile) }
        finally{ if(fs.existsSync(temporary)){fs.unlinkSync(temporary)} }
    }
    return {verified:PACK,active:true,quarantined:planned.length,quarantine}
}
