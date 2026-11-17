const crypto = require('crypto')
const fs = require('fs-extra')
const path = require('path')
const AdmZip = require('adm-zip')
const sha = data => crypto.createHash('sha256').update(data).digest('hex')

exports.install = function(gameDir, module){
    if(!module){
        return { installed: false, count: 0 }
    }
    const artifact = module.artifact
    if(artifact.path !== 'config/magiccodex/pets/pets-models.zip' || !/^[a-f0-9]{64}$/.test(artifact.SHA256 || '')){
        throw new Error('Pet model bundle path or SHA256 is invalid')
    }
    const modelDir = path.join(gameDir, 'config', 'magiccodex', 'pets', 'models')
    const stampPath = path.join(modelDir, '.chacademi-models.json')
    for(const relative of ['config', 'config/magiccodex', 'config/magiccodex/pets', 'config/magiccodex/pets/models']){
        const directory = path.join(gameDir, relative)
        if(fs.existsSync(directory) && fs.lstatSync(directory).isSymbolicLink()){
            throw new Error('Pet model directory cannot be a symbolic link')
        }
    }
    if(fs.existsSync(stampPath)){
        const stamp = fs.readJsonSync(stampPath, { throws: false })
        if(stamp && stamp.sha256 === artifact.SHA256 && stamp.files.length > 0 &&
            stamp.files.every(name => /^[a-zA-Z0-9_-]+\.bbmodel$/.test(name) && fs.existsSync(path.join(modelDir, name)))){
            return { installed: false, count: stamp.files.length }
        }
    }
    const archive = fs.readFileSync(path.join(gameDir, artifact.path))
    if(sha(archive) !== artifact.SHA256){
        throw new Error('Pet model bundle SHA256 mismatch')
    }
    const zip = new AdmZip(archive)
    const manifestEntry = zip.getEntry('manifest.json')
    if(!manifestEntry){
        throw new Error('Pet model bundle manifest is missing')
    }
    const manifest = JSON.parse(manifestEntry.getData().toString('utf8'))
    if(!Array.isArray(manifest.models) || manifest.models.length === 0){
        throw new Error('Pet model bundle has no models')
    }
    const allowed = new Set(['manifest.json', 'models/'])
    const files = new Set()
    for(const model of manifest.models){
        if(!/^models\/[a-zA-Z0-9_-]+\.bbmodel$/.test(model.path) || !/^[a-f0-9]{64}$/.test(model.sha256) || !Number.isSafeInteger(model.bytes) || model.bytes < 0){
            throw new Error('Pet model manifest entry is invalid')
        }
        const name = path.basename(model.path)
        if(files.has(name)){
            throw new Error('Duplicate pet model destination')
        }
        files.add(name)
        allowed.add(model.path)
    }
    if(zip.getEntries().some(entry => !allowed.has(entry.entryName))){
        throw new Error('Unexpected file in pet model bundle')
    }
    fs.ensureDirSync(modelDir)
    for(const model of manifest.models){
        const entry = zip.getEntry(model.path)
        if(!entry){
            throw new Error('Pet model bundle entry is missing')
        }
        const bytes = entry.getData()
        if(bytes.length !== model.bytes || sha(bytes) !== model.sha256){
            throw new Error('Pet model file hash mismatch')
        }
        const target = path.join(modelDir, path.basename(model.path))
        if(fs.existsSync(target)){
            const stat = fs.lstatSync(target)
            if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink > 1){
                throw new Error('Pet model target must be an ordinary file')
            }
        }
        const temporary = target + '.chacademi-' + crypto.randomBytes(8).toString('hex') + '.tmp'
        fs.writeFileSync(temporary, bytes, { flag: 'wx' })
        fs.moveSync(temporary, target, { overwrite: true })
    }
    fs.writeJsonSync(stampPath, { sha256: artifact.SHA256, files: [...files] })
    return { installed: true, count: files.size }
}
