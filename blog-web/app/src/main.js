import {createApp} from 'vue'
import App from './App.vue'
import './styles/theme.css'
import router from './router'

// Keep inbound links from the former hash-based public site working.
if (window.location.hash.startsWith('#/')) {
    const previousRoute = window.location.hash.slice(1)
    const target = previousRoute.startsWith('/article/') ? '/blog' + previousRoute : previousRoute
    // Full navigation ensures the router reads the canonical path on startup.
    window.location.replace(target)
}

const app = createApp(App)
app.use(router)
app.mount('#app')
