'use strict'
function registerLightboxSelection(hexo) {
    hexo.extend.filter.register('before_generate', () => {
        const theme = hexo.theme.config
        // 与主题实际选择 mediumZoom 的优先级保持一致，只调整本次构建的派生上下文。
        if (theme.medium_zoom === true && theme.fancybox === true) theme.fancybox = false
    }, 1000)
}
module.exports = {registerLightboxSelection}
if (typeof hexo !== 'undefined') registerLightboxSelection(hexo)
