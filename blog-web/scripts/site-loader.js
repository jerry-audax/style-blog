'use strict'

// Hexo only discovers plugins in scripts/. Keep implementation and CLI tools
// in tools/, but load these two plugins with this Hexo instance and await them.
// require() alone would not provide their instance-scoped `hexo` binding.
const path = require('node:path')
for (const plugin of ['site-integration.cjs', 'vendor-assets.cjs']) {
    await hexo.loadPlugin(path.join(hexo.base_dir, 'tools', plugin))
}
