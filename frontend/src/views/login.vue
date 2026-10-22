<template>
  <div class="login">
    <div class="login-shell">
      <el-form ref="loginRef" :model="loginForm" :rules="loginRules" class="login-form">
        <div class="title-box">
          <h1 class="title">工作台登录</h1>
          <lang-select v-if="showLanguageSwitch" />
        </div>

        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            type="text"
            size="large"
            auto-complete="off"
            :placeholder="$t('login.username')"
          >
            <template #prefix><svg-icon icon-class="user" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            size="large"
            auto-complete="off"
            :placeholder="$t('login.password')"
            @keyup.enter="handleLogin"
          >
            <template #prefix><svg-icon icon-class="password" class="el-input__icon input-icon" /></template>
          </el-input>
        </el-form-item>

        <el-form-item v-if="captchaEnabled" prop="code" class="captcha-row">
          <el-input
            v-model="loginForm.code"
            size="large"
            auto-complete="off"
            :placeholder="$t('login.code')"
            @keyup.enter="handleLogin"
          >
            <template #prefix><svg-icon icon-class="validCode" class="el-input__icon input-icon" /></template>
          </el-input>
          <div class="login-code">
            <img :src="codeUrl" class="login-code-img" @click="getCode" />
          </div>
        </el-form-item>

        <div class="form-meta">
          <el-checkbox v-model="loginForm.rememberMe">记住用户名</el-checkbox>
          <router-link v-if="register" class="link-type" :to="'/register'">
            {{ $t('login.switchRegisterPage') }}
          </router-link>
        </div>

        <div v-if="showSocialLogin" class="social-panel">
          <span class="social-label">第三方登录</span>
          <div class="social-actions">
            <el-button circle :title="$t('login.social.wechat')" @click="doSocialLogin('wechat')">
              <svg-icon icon-class="wechat" />
            </el-button>
            <el-button circle :title="$t('login.social.maxkey')" @click="doSocialLogin('maxkey')">
              <svg-icon icon-class="maxkey" />
            </el-button>
            <el-button circle :title="$t('login.social.topiam')" @click="doSocialLogin('topiam')">
              <svg-icon icon-class="topiam" />
            </el-button>
            <el-button circle :title="$t('login.social.gitee')" @click="doSocialLogin('gitee')">
              <svg-icon icon-class="gitee" />
            </el-button>
            <el-button circle :title="$t('login.social.github')" @click="doSocialLogin('github')">
              <svg-icon icon-class="github" />
            </el-button>
          </div>
        </div>

        <el-form-item class="submit-row">
          <el-button :loading="loading" size="large" type="primary" class="submit-button" @click.prevent="handleLogin">
            <span v-if="!loading">{{ $t('login.login') }}</span>
            <span v-else>{{ $t('login.logging') }}</span>
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { to } from 'await-to-js';
import { useI18n } from 'vue-i18n';
import { getCodeImg } from '@/api/login';
import { authRouterUrl } from '@/api/system/social/auth';
import { LoginData } from '@/api/types';
import { HttpStatus } from '@/enums/RespEnum';
import { useUserStore } from '@/store/modules/user';

// 仅在部署时明确启用后显示这些可选入口，默认只提供账号登录。
const showSocialLogin = import.meta.env.VITE_APP_SOCIAL_LOGIN_ENABLED === 'true';
const showLanguageSwitch = import.meta.env.VITE_APP_LANGUAGE_SWITCH_ENABLED === 'true';
const userStore = useUserStore();
const router = useRouter();
const { t } = useI18n();

const loginForm = ref<LoginData>({
  username: '',
  password: '',
  rememberMe: false,
  code: '',
  uuid: ''
} as LoginData);

const loginRules: ElFormRules = {
  username: [
    {
      required: true,
      trigger: 'blur',
      message: t('login.rule.username.required')
    }
  ],
  password: [
    {
      required: true,
      trigger: 'blur',
      message: t('login.rule.password.required')
    }
  ],
  code: [
    {
      required: true,
      trigger: 'change',
      message: t('login.rule.code.required')
    }
  ]
};

const codeUrl = ref('');
const loading = ref(false);
const captchaEnabled = ref(true);
const register = ref(false);
const redirect = ref('/');
const loginRef = ref<ElFormInstance>();

watch(
  () => router.currentRoute.value,
  (newRoute: any) => {
    redirect.value = newRoute.query && newRoute.query.redirect && decodeURIComponent(newRoute.query.redirect);
  },
  { immediate: true }
);

const handleLogin = () => {
  loginRef.value?.validate(async (valid: boolean, fields: any) => {
    if (valid) {
      loading.value = true;
      if (loginForm.value.rememberMe) {
        localStorage.setItem('rememberedUsername', String(loginForm.value.username));
      } else {
        localStorage.removeItem('rememberedUsername');
      }
      localStorage.removeItem('password');
      const [err] = await to(userStore.login(loginForm.value));
      if (!err) {
        const redirectUrl = redirect.value || '/';
        await router.push(redirectUrl);
        loading.value = false;
      } else {
        loading.value = false;
        if (captchaEnabled.value) {
          await getCode();
        }
      }
    } else {
      console.log('error submit!', fields);
    }
  });
};

const getCode = async () => {
  const res = await getCodeImg();
  const { data } = res;
  captchaEnabled.value = data.captchaEnabled === undefined ? true : data.captchaEnabled;
  if (captchaEnabled.value) {
    loginForm.value.code = '';
    codeUrl.value = 'data:image/gif;base64,' + data.img;
    loginForm.value.uuid = data.uuid;
  }
};

const getLoginData = () => {
  // 清除旧版凭据缓存，只恢复用户在新版明确选择记住的用户名。
  localStorage.removeItem('username');
  localStorage.removeItem('password');
  localStorage.removeItem('rememberMe');
  const username = localStorage.getItem('rememberedUsername') || '';
  loginForm.value.username = username;
  loginForm.value.password = '';
  loginForm.value.rememberMe = Boolean(username);
};

const doSocialLogin = (type: string) => {
  authRouterUrl(type).then((res: any) => {
    if (res.code === HttpStatus.SUCCESS) {
      window.location.href = res.data;
    } else {
      ElMessage.error(res.msg);
    }
  });
};

onMounted(() => {
  getCode();
  getLoginData();
});
</script>

<style lang="scss" scoped>
.login {
  min-height: 100vh;
  min-height: 100svh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at 12% 12%, rgba(53, 109, 255, 0.22), transparent 24%),
    radial-gradient(circle at 88% 18%, rgba(14, 165, 233, 0.18), transparent 24%),
    linear-gradient(135deg, #071120 0%, #0f1b33 42%, #15345f 100%);
}

.login-shell {
  width: min(440px, 100%);
}

.login-form {
  border-radius: 28px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow: 0 30px 80px rgba(2, 8, 23, 0.32);
  backdrop-filter: blur(18px);
  width: 100%;
  padding: 34px 30px 26px;
  z-index: 1;
  background: var(--app-surface-bg);
}

.title-box {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 28px;

  .title {
    margin: 0;
    color: var(--app-text-title);
    font-weight: 700;
    font-size: 30px;
    letter-spacing: -0.03em;
  }

  :deep(.lang-select--style) {
    line-height: 0;
    color: var(--app-text-muted);
    padding: 10px;
    border-radius: 14px;
    background: var(--app-elevated-soft-bg);
    border: 1px solid var(--app-surface-border);
  }
}

.login-form .el-input {
  height: 48px;
}

.login-form .input-icon {
  height: 46px;
  width: 14px;
  margin-left: 0;
}

.captcha-row {
  :deep(.el-form-item__content) {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 122px;
    gap: 12px;
  }
}

.form-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: -2px 0 18px;
}

.social-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  margin-bottom: 22px;
  border-radius: 20px;
  background: var(--app-elevated-soft-bg);
  border: 1px solid var(--app-surface-border);
}

.social-label {
  color: var(--app-text-muted);
  font-size: 13px;
  font-weight: 600;
}

.social-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.submit-row {
  margin-bottom: 0;
}

.submit-button {
  width: 100%;
  height: 50px;
  border-radius: 16px;
  box-shadow: 0 18px 34px rgba(53, 109, 255, 0.22);
}

.login-form :deep(.el-input__wrapper) {
  min-height: 48px;
  background-color: var(--el-bg-color);
  border-radius: 16px;
  box-shadow: 0 0 0 1px var(--app-surface-border) inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px rgba(53, 109, 255, 0.24) inset,
    0 0 0 4px rgba(53, 109, 255, 0.12);
}

.login-form :deep(.el-checkbox__label) {
  color: var(--app-text-muted);
}

.login-form :deep(.el-button.is-circle) {
  background: var(--app-elevated-soft-bg);
  border: 1px solid var(--app-surface-border);
  color: var(--app-text-muted);
}

.login-form :deep(.el-button.is-circle:hover) {
  background: rgba(53, 109, 255, 0.12);
  border-color: rgba(53, 109, 255, 0.2);
  color: var(--app-accent-strong);
}

.login-code {
  height: 48px;
  box-sizing: border-box;
  border-radius: 16px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--app-surface-border);

  img {
    cursor: pointer;
    vertical-align: middle;
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.login-code-img {
  height: 48px;
  padding-left: 0;
}

@media (max-width: 640px) {
  .login {
    padding: 24px 14px;
  }

  .login-form {
    padding: 26px 18px 20px;
  }

  .title-box {
    flex-direction: column;
  }

  .social-panel {
    flex-direction: column;
    align-items: flex-start;
  }

  .social-actions {
    justify-content: flex-start;
  }

  .captcha-row :deep(.el-form-item__content) {
    grid-template-columns: 1fr;
  }
}
</style>
