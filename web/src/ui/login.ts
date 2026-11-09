import { Auth } from '../auth';
import { $ } from '../shared';

const err = $('#error') as HTMLParagraphElement;
const username = $('#username') as HTMLInputElement;
const password = $('#password') as HTMLInputElement;
const btn = $('#login-btn') as HTMLButtonElement;
const toRegister = $('#to-register') as HTMLAnchorElement;

let isRegisterMode = false;

// Переключение между входом и регистрацией
toRegister.onclick = (e) => {
    e.preventDefault();
    isRegisterMode = !isRegisterMode;
    document.querySelector('h1')!.textContent = isRegisterMode ? 'Регистрация' : 'Вход';
    btn.textContent = isRegisterMode ? 'Создать аккаунт' : 'Войти';
    toRegister.textContent = isRegisterMode ? 'Уже есть аккаунт? Войти' : 'Нет аккаунта? Зарегистрироваться';
    err.textContent = '';
};

btn.onclick = async () => {
    err.textContent = '';
    btn.disabled = true;
    try {
        if (isRegisterMode) {
            // Регистрация (через API)
            const { api } = await import('../api');
            await api.auth.register(username.value, password.value);
        }
        // Вход (создаёт сессию на сервере)
        await Auth.login(username.value, password.value);
        location.href = '/buildings.html';
    } catch (e: any) {
        err.textContent = e.message || 'Ошибка входа';
    } finally {
        btn.disabled = false;
    }
};