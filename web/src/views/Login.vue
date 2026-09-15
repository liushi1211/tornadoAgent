<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: ''
})

const title = computed(() => (mode.value === 'login' ? '登录' : '注册'))

// 密码规则：8-32 位，且同时包含字母与数字（§7 行 1）
const passwordRule = {
  min: 8,
  max: 32,
  pattern: /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d!@#$%^&.*_\-]{8,32}$/,
  message: '密码需 8-32 位，且同时包含字母和数字'
}

const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '用户名长度 3-64 位', trigger: 'blur' }
  ],
  password:
    mode.value === 'register'
      ? [{ required: true, message: '请输入密码', trigger: 'blur' }, passwordRule]
      : [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirmPassword:
    mode.value === 'register'
      ? [
          { required: true, message: '请再次输入密码', trigger: 'blur' },
          {
            validator: (_r: unknown, v: string, cb: (e?: Error) => void) =>
              v !== form.password ? cb(new Error('两次输入的密码不一致')) : cb(),
            trigger: 'blur'
          }
        ]
      : [],
  email:
    mode.value === 'register'
      ? [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
      : []
}))

function switchMode(m: 'login' | 'register') {
  mode.value = m
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    if (mode.value === 'login') {
      await userStore.login({ username: form.username, password: form.password })
    } else {
      await userStore.register({
        username: form.username,
        password: form.password,
        email: form.email || undefined
      })
    }
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功，已自动登录')
    const redirect = (route.query.redirect as string) || '/chat'
    router.replace(redirect)
  } catch {
    /* 错误 toast 已由 axios 拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-brand">
        <el-icon :size="34" color="#409eff"><Cpu /></el-icon>
        <h1>Spring AI Alibaba 智能体工作台</h1>
        <p>对话 · Skill · MCP · RAG · 记忆</p>
      </div>

      <el-radio-group v-model="mode" class="mode-switch" @change="switchMode(mode)">
        <el-radio-button value="login">登录</el-radio-button>
        <el-radio-button value="register">注册</el-radio-button>
      </el-radio-group>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="8-32 位，含字母和数字" show-password>
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <template v-if="mode === 'register'">
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="邮箱（选填）" prop="email">
            <el-input v-model="form.email" placeholder="you@example.com" clearable>
              <template #prefix><el-icon><Message /></el-icon></template>
            </el-input>
          </el-form-item>
        </template>

        <el-button type="primary" class="submit-btn" :loading="loading" @click="submit">
          {{ title }}
        </el-button>
      </el-form>

      <p class="login-tip">登录成功后颁发双 Token（Access 2h + Refresh 7d），请求自动携带 Bearer。</p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e0eafc 0%, #cfdef3 100%);
  padding: 24px;
}
.login-card {
  width: 420px;
  max-width: 100%;
  background: #fff;
  border-radius: 16px;
  padding: 36px 34px 26px;
  box-shadow: 0 12px 40px rgba(31, 45, 61, 0.15);
}
.login-brand {
  text-align: center;
  margin-bottom: 18px;
}
.login-brand h1 {
  font-size: 20px;
  margin: 10px 0 4px;
}
.login-brand p {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
.mode-switch {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}
.submit-btn {
  width: 100%;
  margin-top: 6px;
}
.login-tip {
  margin-top: 18px;
  font-size: 12px;
  color: #a8abb2;
  text-align: center;
  line-height: 1.6;
}
</style>
