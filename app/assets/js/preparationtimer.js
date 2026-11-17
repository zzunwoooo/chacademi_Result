const {performance} = require('perf_hooks')
class PreparationTimer {
    constructor(){ this.last = performance.now() }
    mark(stage){
        const now = performance.now()
        console.info('[PreparationTiming]', JSON.stringify({stage, milliseconds:Math.round(now - this.last)}))
        this.last = now
    }
}
module.exports = PreparationTimer
