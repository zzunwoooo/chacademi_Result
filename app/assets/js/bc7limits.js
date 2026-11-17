const crypto = require('crypto')
const fs = require('fs-extra')

// This migration changes only the two catalog-count limits; byte budgets stay untouched.
exports.migrate = async function(target){
    const original = await fs.readFile(target)
    let text = original.toString('utf8')
    for(const key of ['resident-systems', 'prewarm-systems']){
        const pattern = new RegExp('^([ \\t]*' + key + '[ \\t]*[=:][ \\t]*)[^\\r\\n]*', 'gm')
        const matches = [...text.matchAll(pattern)]
        if(matches.length > 1){ throw new Error('Duplicate BC7 system limit') }
        if(matches.length){ text = text.replace(pattern, (match, prefix) => prefix + '1024') }
        else{ text += (text.endsWith('\n') ? '' : '\n') + key + '=1024\n' }
    }
    const updated = Buffer.from(text, 'utf8')
    if(original.equals(updated)){ return false }
    const suffix = crypto.randomBytes(8).toString('hex')
    const backup = target + '.before-bc7-' + suffix
    await fs.writeFile(backup, original, { flag: 'wx' })
    const temporary = target + '.bc7-' + suffix + '.tmp'
    try{
        await fs.writeFile(temporary, updated, { flag: 'wx' })
        await fs.move(temporary, target, { overwrite: true })
    }finally{
        await fs.remove(temporary)
    }
    return true
}
