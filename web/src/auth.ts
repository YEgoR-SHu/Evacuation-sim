import { api } from './api';
import type { User } from './types';

export const Auth = {
    currentUser: null as User | null,
    async login(username: string, password: string) {
        this.currentUser = await api.auth.login(username, password);
        return this.currentUser;
    },
    async logout() {
        await api.auth.logout();
        this.currentUser = null;
        location.href = '/login.html';
    },
    async check() {
        try {
            this.currentUser = await api.auth.me();
            return true;
        } catch {
            this.currentUser = null;
            return false;
        }
    },
    async requireAuth(): Promise<boolean> {
        const ok = await this.check();
        if (!ok) location.href = '/login.html';
        return ok;
    },
    renderHeader(title: string) {
        document.querySelector('header[data-app-header]')?.remove();
        const header = document.createElement('header');
        header.dataset.appHeader = '';
        const name = document.createElement('b');
        name.textContent = title;
        const buildings = document.createElement('a');
        buildings.href = '/buildings.html';
        buildings.textContent = 'Мои здания';
        const logout = document.createElement('button');
        logout.id = 'logout';
        logout.textContent = 'Выйти';
        logout.onclick = async () => {
            logout.disabled = true;
            try { await this.logout(); }
            catch { logout.disabled = false; }
        };
        header.append(name, buildings, logout);
        document.body.prepend(header);
    }
};
