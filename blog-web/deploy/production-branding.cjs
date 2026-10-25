'use strict'
// Production-only Hexo override; retain local theme configuration.
hexo.extend.filter.register('before_generate', () => {
    hexo.theme.config.preloader.avatar = '/blog/img/avatar.jpg'
})
