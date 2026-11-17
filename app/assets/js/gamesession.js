const fs = require('fs')
const path = require('path')
function file(root){ return path.join(root, '.chacademi-game-session.json') }
exports.check = root => {
    if(!fs.existsSync(file(root))){ return }
    const session = JSON.parse(fs.readFileSync(file(root), 'utf8'))
    if(!Number.isInteger(session.pid) || session.pid <= 0){ throw new Error('Invalid game session marker') }
    try { process.kill(session.pid, 0) } catch(error){
        if(error.code === 'ESRCH'){ fs.unlinkSync(file(root)); return }
        throw error
    }
    throw new Error('이미 실행 중인 게임이 있습니다. 게임 종료 후 다시 시도하세요.')
}
exports.track = (root, child) => {
    child.once('spawn', () => {
        try { fs.writeFileSync(file(root), JSON.stringify({pid: child.pid}), {flag: 'wx'}) }
        catch(error){ console.error('Game session marker:', error.message) }
    })
    child.once('close', () => {
        try {
            if(JSON.parse(fs.readFileSync(file(root), 'utf8')).pid === child.pid){ fs.unlinkSync(file(root)) }
        } catch(error){ if(error.code !== 'ENOENT'){ console.error('Game session cleanup:', error.message) } }
    })
}
