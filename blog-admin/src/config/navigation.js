// Only expose features supported by this single-owner blog, not the reference app's full menu.
export const adminNavigation = [
    {label: '工作台', items: [{to: '/dashboard', label: '概览', icon: 'Odometer'}]},
    {label: '内容管理', items: [
        {to: '/articles', label: '文章', icon: 'Document'},
        {to: '/categories', label: '分类', icon: 'Collection'},
        {to: '/tags', label: '标签', icon: 'PriceTag'},
        {to: '/comments', label: '历史评论', icon: 'ChatLineSquare'},
    ]},
    {label: '资源与站点', items: [
        {to: '/images', label: '图片库', icon: 'Picture'},
        {to: '/music', label: '音乐', icon: 'Headset'},
        {to: '/surprise', label: '惊喜视频', icon: 'VideoCamera'},
        {to: '/account', label: '博主设置', icon: 'User'},
        {to: '/logs', label: '操作日志', icon: 'Operation'},
    ]},
]
