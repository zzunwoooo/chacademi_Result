const fs = require('fs')
const path = require('path')
// Only rows visible in the user's supplied key reference. Other keys stay untouched.
const DEFAULTS = {
    'key_key.sprint':'key.keyboard.left.shift',
    'key_key.inventory':'key.keyboard.tab',
    'key_key.playerlist':'key.keyboard.grave.accent',
    'key_key.sneak':'key.keyboard.c',
    'key_key.magiccodex.magic_mode':'key.keyboard.z',
    'key_key.magiccodex.artifact_use':'key.keyboard.x',
    'key_key.magiccodex.stats':'key.keyboard.i',
    'key_key.magiccodex.equipment':'key.keyboard.o',
    'key_key.magiccodex.open':'key.keyboard.k',
    'key_key.wi_zoom.zoom':'key.keyboard.equal',
    'key_key.plasmovoice.settings':'key.keyboard.n'
}
// Only known shipped defaults are released, only for a fresh instance.
// These features remain available for the user to assign another key in Controls.
const CONFLICTING_DEFAULTS = {
    'key_key.socialInteractions':'key.keyboard.p',
    'key_key.loadToolbarActivator':'key.keyboard.x',
    'key_key.advancements':'key.keyboard.l',
    'key_key.journeymap.minimap_preset':'key.keyboard.backslash',
    'key_key.journeymap.fullscreen_chat_position':'key.keyboard.c',
    'key_key.journeymap.fullscreen_waypoints':'key.keyboard.n',
    'key_key.journeymap.fullscreen_options':'key.keyboard.o',
    'key_key.koreanpatch.toggle_ime':'key.keyboard.i',
    'key_key.shouldersurfing.swap_shoulder':'key.keyboard.o',
    'key_iris.keybind.toggleShaders':'key.keyboard.k',
    'key_iris.keybind.shaderPackSelection':'key.keyboard.o'
}
// P/L/Backslash/Ctrl belong to UiKeys, whose shipped defaults already match the image.
// Preserve ui-keys.properties and spell key-settings.yml, including collision protection.
exports.capture = root => ({fresh: !fs.existsSync(path.join(root, 'options.txt'))})
exports.apply = (root, state) => {
    const file = path.join(root, 'options.txt')
    if(fs.existsSync(file) && (!fs.lstatSync(file).isFile() || fs.lstatSync(file).isSymbolicLink())){
        throw new Error('options.txt must be an ordinary file')
    }
    const text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
    const newline = text.includes('\r\n') ? '\r\n' : '\n'
    const lines = text.split(/\r?\n/)
    const seen = new Set()
    const result = lines.map(line => {
        const separator = line.indexOf(':')
        const key = line.slice(0, separator)
        if(separator < 0){ return line }
        if(state.fresh && Object.hasOwn(CONFLICTING_DEFAULTS, key) && line.slice(separator + 1).replace(/\r+$/,'') === CONFLICTING_DEFAULTS[key]){
            return key + ':key.keyboard.unknown'
        }
        if(!Object.hasOwn(DEFAULTS, key)){ return line }
        seen.add(key)
        return state.fresh ? key + ':' + DEFAULTS[key] : line
    })
    for(const [key, value] of Object.entries(DEFAULTS)){
        if(!seen.has(key)){ result.push(key + ':' + value) }
    }
    const output = result.join(newline)
    if(output === text){ return }
    fs.mkdirSync(root, {recursive:true})
    const backup = file + '.chacademi-key-defaults-backup'
    if(fs.existsSync(file) && !fs.existsSync(backup)){ fs.copyFileSync(file, backup, fs.constants.COPYFILE_EXCL) }
    fs.writeFileSync(file + '.chacademi-key-defaults-tmp', output, {flag:'wx'})
    fs.renameSync(file + '.chacademi-key-defaults-tmp', file)
}
