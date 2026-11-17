const fs = require('fs-extra')
const path = require('path')

exports.prepare = async function(distribution, instanceDirectory){
    const copy = structuredClone(distribution.rawDistribution)
    for(const server of copy.servers){
        const root = path.resolve(instanceDirectory, server.id)
        async function visit(modules){
            for(const module of modules){
                if(module.updatePolicy === 'seed'){
                    const relative = module.artifact.path
                    if(module.type !== 'File' || typeof relative !== 'string' ||
                        !(relative === 'options.txt' || relative.startsWith('config/') || relative.endsWith('.zip.txt')) ||
                        relative.includes('\\') || relative.includes(':') || relative.split('/').includes('..')){
                        throw new Error('Unsafe seed configuration module')
                    }
                    const target = path.resolve(root, relative)
                    if(!target.startsWith(root + path.sep)){
                        throw new Error('Seed path outside instance')
                    }
                    module.seedOriginalMD5 ??= module.artifact.MD5
                    module.artifact.MD5 = module.seedOriginalMD5
                    if(await fs.pathExists(target)){
                        const stat = await fs.lstat(target)
                        if(!stat.isFile() || stat.isSymbolicLink()){
                            throw new Error('Seed target must be an ordinary file')
                        }
                        if(relative === 'config/portablevfx/load-limits.properties' && module.vfxCatalogLimits){
                            await require('./vfxlimits').migrate(target, module.vfxCatalogLimits)
                        }
                        if(relative === 'config/portablevfx/load-limits.properties' && module.bc7SystemLimit === 1024){
                            await require('./bc7limits').migrate(target)
                        }
                        module.artifact.MD5 = null
                    }
                }
                if(module.subModules){ await visit(module.subModules) }
            }
        }
        await visit(server.modules)
    }
    return copy
}
