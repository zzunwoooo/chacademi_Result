// Preserve every unrelated NBT tag and server entry byte-for-byte.
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')
const SERVER_ID = 'launcher_v2-1.21.4'
const ADDRESS = '스트리머.온라인.한국'
const ASCII_ADDRESS = require('url').domainToASCII(ADDRESS)
exports.isChacademi = server => server.id === SERVER_ID && server.address === ADDRESS
function str(value){
    const b = Buffer.from(value, 'utf8'), size = Buffer.alloc(2)
    size.writeUInt16BE(b.length)
    return Buffer.concat([size, b])
}
function tag(type, name, data){ return Buffer.concat([Buffer.from([type]), str(name), data]) }
function compound(tags){ return Buffer.concat([...tags, Buffer.from([0])]) }
function parse(buffer){
    let offset = 0
    const take = count => {
        if(count < 0 || offset + count > buffer.length){ throw new Error('Invalid servers.dat NBT') }
        const b = buffer.subarray(offset, offset + count); offset += count; return b
    }
    const string = () => take(take(2).readUInt16BE()).toString('utf8')
    function payload(type, depth = 0){
        if(depth > 32){ throw new Error('NBT nesting limit') }
        const sizes = {1:1, 2:2, 3:4, 4:8, 5:4, 6:8}
        if(sizes[type]){ take(sizes[type]); return }
        if(type === 8){ string(); return }
        if([7,11,12].includes(type)){
            const n = take(4).readInt32BE()
            if(n < 0){ throw new Error('Negative NBT array') }
            take(n * ({7:1,11:4,12:8}[type])); return
        }
        if(type === 9){
            const itemType = take(1)[0], n = take(4).readInt32BE()
            if(n < 0 || n > 100000){ throw new Error('Invalid NBT list') }
            for(let i = 0; i < n; i++){ payload(itemType, depth + 1) }
            return
        }
        if(type === 10){ while(take(1)[0] !== 0){ offset--; named(depth + 1) }; return }
        throw new Error('Unsupported NBT tag')
    }
    function named(depth = 0){
        const start = offset, type = take(1)[0], name = string(), body = offset
        payload(type, depth)
        return {type, name, body, end: offset, raw: buffer.subarray(start, offset)}
    }
    return {named, string, take, payload, get offset(){ return offset }, set offset(v){ offset = v }}
}
exports.prepare = (root, server, connectionAddress = ADDRESS + ':25565') => {
    if(!exports.isChacademi(server)){ return false }
    if(![ADDRESS, ADDRESS + ':25565', ASCII_ADDRESS, ASCII_ADDRESS + ':25565'].includes(connectionAddress)){
        throw new Error('Unexpected Chacademi connection address')
    }
    const target = path.join(root, 'servers.dat')
    if(fs.existsSync(target) && (!fs.lstatSync(target).isFile() || fs.lstatSync(target).isSymbolicLink())){
        throw new Error('servers.dat must be an ordinary file')
    }
    let bytes = fs.existsSync(target) ? fs.readFileSync(target) : tag(10, '', compound([]))
    const gzip = bytes[0] === 31 && bytes[1] === 139
    if(gzip){ bytes = zlib.gunzipSync(bytes, {maxOutputLength: 8 * 1024 * 1024}) }
    if(bytes.length > 8 * 1024 * 1024){ throw new Error('servers.dat too large') }
    const p = parse(bytes), rootTag = p.named()
    if(rootTag.type !== 10 || rootTag.end !== bytes.length){ throw new Error('Invalid root servers.dat') }
    p.offset = rootTag.body
    const rootTags = []
    let entries = [], foundList = false
    while(bytes[p.offset] !== 0){
        const t = p.named()
        if(t.name !== 'servers'){ rootTags.push(t.raw); continue }
        if(foundList || t.type !== 9){ throw new Error('Invalid servers list') }
        foundList = true
        const end = p.offset
        p.offset = t.body
        if(p.take(1)[0] !== 10){ throw new Error('Invalid server list type') }
        const count = p.take(4).readInt32BE()
        if(count < 0 || count > 10000){ throw new Error('Invalid server count') }
        for(let i = 0; i < count; i++){
            const tags = [], start = p.offset
            let address
            while(bytes[p.offset] !== 0){
                const field = p.named()
                if(field.name === 'ip' && field.type === 8){
                    const saved = p.offset; p.offset = field.body; address = p.string(); p.offset = saved
                }
                tags.push(field)
            }
            p.take(1)
            const matches = [ADDRESS, ADDRESS + ':25565', ASCII_ADDRESS, ASCII_ADDRESS + ':25565'].includes(address)
            entries.push(matches ? compound([...tags.filter(t => !['acceptTextures', 'ip'].includes(t.name)).map(t => t.raw), tag(8, 'ip', str(connectionAddress)), tag(1, 'acceptTextures', Buffer.from([1]))]) : bytes.subarray(start, p.offset))
        }
        if(p.offset !== t.end){ throw new Error('Invalid server list boundary') }
        p.offset = end
    }
    // Quick Play reads preferences from the matching saved server entry.
    if(!entries.some(entry => {
        const q = parse(tag(10, '', entry)); const t = q.named(); q.offset = t.body
        while(entry.length && q.offset < t.end - 1){
            const f = q.named()
            if(f.name === 'ip' && f.type === 8){ q.offset = f.body; const a = q.string(); return a === connectionAddress }
        }
        return false
    })){
        entries.push(compound([tag(8, 'name', str('차카데미')), tag(8, 'ip', str(connectionAddress)), tag(1, 'acceptTextures', Buffer.from([1]))]))
    }
    const count = Buffer.alloc(4); count.writeInt32BE(entries.length)
    const output = tag(10, '', compound([...rootTags, tag(9, 'servers', Buffer.concat([Buffer.from([10]), count, ...entries]))]))
    fs.mkdirSync(root, {recursive: true})
    if(fs.existsSync(target) && !fs.existsSync(target + '.chacademi-backup')){ fs.copyFileSync(target, target + '.chacademi-backup', fs.constants.COPYFILE_EXCL) }
    const temp = target + '.chacademi-tmp'
    fs.writeFileSync(temp, gzip ? zlib.gzipSync(output) : output, {flag: 'wx'})
    fs.renameSync(temp, target)
    return true
}
