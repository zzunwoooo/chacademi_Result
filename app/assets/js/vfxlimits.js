const fs = require('fs-extra')

exports.migrate = async function(file, limits){
    if(!Number.isInteger(limits.encodedCacheMiB) || limits.encodedCacheMiB < 16 || limits.encodedCacheMiB > 1024 ||
        !Number.isInteger(limits.systems) || limits.systems < 1 || limits.systems > 4096){
        throw new Error('Invalid VFX catalogue limits')
    }
    const wanted = new Map([
        ['encoded-cache-mib', limits.encodedCacheMiB],
        ['resident-systems', limits.systems],
        ['prewarm-systems', limits.systems]
    ])
    const original = await fs.readFile(file, 'utf8')
    const newline = original.includes('\r\n') ? '\r\n' : '\n'
    const seen = new Set()
    const lines = original.split(/\r?\n/).map(line => {
        const match = /^(\s*([^#!\s=:]+)\s*[:=]\s*)(.*?)\s*$/.exec(line)
        if(!match || !wanted.has(match[2])){ return line }
        const key = match[2]
        if(seen.has(key)){ throw new Error('Duplicate VFX limit: ' + key) }
        seen.add(key)
        if(!/^\d+$/.test(match[3])){ throw new Error('Invalid VFX limit: ' + key) }
        const value = Number(match[3])
        return value < wanted.get(key) ? match[1] + wanted.get(key) : line
    })
    const added = [...wanted].filter(([key]) => !seen.has(key)).map(([key, value]) => key + '=' + value)
    if(added.length){
        if(lines.at(-1) === ''){ lines.pop() }
        lines.push(...added, '')
    }
    const next = lines.join(newline)
    if(next === original){ return false }
    const temporary = file + '.vfx-limits.tmp-' + process.pid
    try {
        await fs.writeFile(temporary, next, { flag: 'wx' })
        await fs.rename(temporary, file)
    } finally {
        await fs.remove(temporary)
    }
    return true
}