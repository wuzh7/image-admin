import { LockOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormCheckbox, ProFormText } from '@ant-design/pro-components';
import { history, useSearchParams } from '@umijs/max';
import { Button, ConfigProvider, message } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useCallback, useEffect, useState } from 'react';
import type { VerifyCodeResult } from '@/api/types';
import { getCodeImg, getInfo, login } from '@/api/login';
import { isRelogin } from '@/api/request';
import { useTagsViewStore } from '@/stores/tagsViewStore';
import { getToken, removeToken, setToken } from '@/utils/auth';
import { appEnv } from '@/utils/env';

const DEFAULT_LOGIN_REDIRECT = '/index';

function normalizeLoginRedirect(redirect?: string | null) {
  if (!redirect) return DEFAULT_LOGIN_REDIRECT;
  if (!redirect.startsWith('/') || redirect.startsWith('//')) return DEFAULT_LOGIN_REDIRECT;
  if (redirect === '/login' || redirect.startsWith('/login?')) return DEFAULT_LOGIN_REDIRECT;
  return redirect;
}

function getRememberedLogin() {
  if (typeof window === 'undefined') return { rememberMe: false };
  const rememberMe = localStorage.getItem('rememberMe') === 'true';
  const username = localStorage.getItem('username') || undefined;
  return {
    username: rememberMe ? username : 'admin',
    password: rememberMe ? undefined : 'admin123',
    rememberMe
  };
}

const authText = {
  title: '图片管理后台',
  brandDesc: '用于商品图片上传、相似度检测、人工审核和正式图库管理。',
  highlights: ['批量上传', '相似度检测', '人工审核', '图库管理'],
  metrics: [
    { value: 'React+Ant', label: '前端技术栈' },
    { value: '动态权限控制', label: '细粒度权限管理' },
    { value: '全栈技术集成', label: '主流技术栈' }
  ],
  formSubTitle: '使用现有后端账号登录',
  username: '用户名',
  password: '密码',
  code: '验证码',
  rememberMe: '记住我',
  register: '注册账号',
  loginSuccess: '登录成功',
  loginFail: '登录失败',
  usernameRequired: '请输入用户名',
  passwordRequired: '请输入密码',
  codeRequired: '请输入验证码'
};

export default function Login() {
  const [params] = useSearchParams();
  const resetTags = useTagsViewStore(state => state.resetTags);
  const [captcha, setCaptcha] = useState<VerifyCodeResult>({ captchaEnabled: true });
  const [initialValues] = useState(getRememberedLogin);
  const text = authText;
  const redirect = params.get('redirect');

  const loadCaptcha = useCallback(async () => {
    const res = await getCodeImg();
    setCaptcha({
      ...res.data,
      captchaEnabled: res.data.captchaEnabled === undefined ? true : res.data.captchaEnabled
    });
  }, []);

  useEffect(() => {
    let active = true;
    const initialize = async () => {
      if (getToken()) {
        try {
          await getInfo(true);
          if (active) history.replace(normalizeLoginRedirect(redirect));
          return;
        } catch {
          removeToken();
        }
      }
      if (!active) return;
      try {
        await loadCaptcha();
      } catch {
        if (active) setCaptcha({ captchaEnabled: false });
      }
    };
    void initialize();
    return () => {
      active = false;
    };
  }, [loadCaptcha, redirect]);

  useEffect(() => {
    document.title = appEnv.title;
  }, []);

  return (
    <ConfigProvider locale={zhCN}>
      <div className="login-page">
        <section className="login-brand">
          <h1>{text.title}</h1>
          <p>{text.brandDesc}</p>
          <div className="login-brand-highlights">
            {text.highlights.map(item => (
              <span key={item}>{item}</span>
            ))}
          </div>
          <div className="login-brand-metrics">
            {text.metrics.map(item => (
              <article key={item.label}>
                <strong>{item.value}</strong>
                <span>{item.label}</span>
              </article>
            ))}
          </div>
        </section>
        <section className="login-panel">
          <div className="login-panel-inner">
            <LoginForm
              title={appEnv.logoTitle}
              subTitle={text.formSubTitle}
              initialValues={initialValues}
              onFinish={async values => {
                try {
                  if (values.rememberMe) {
                    localStorage.setItem('username', String(values.username || ''));
                    localStorage.setItem('rememberMe', 'true');
                  } else {
                    localStorage.removeItem('username');
                    localStorage.removeItem('rememberMe');
                  }
                  localStorage.removeItem('password');
                  const res = await login({
                    username: values.username,
                    password: values.password,
                    code: values.code,
                    uuid: captcha.uuid,
                    rememberMe: values.rememberMe
                  });
                  setToken(res.data.access_token);
                  isRelogin.show = false;
                  resetTags();
                  message.success(text.loginSuccess);
                  history.replace(normalizeLoginRedirect(redirect));
                  return true;
                } catch (error) {
                  if (captcha.captchaEnabled) {
                    loadCaptcha();
                  }
                  message.error((error as Error).message || text.loginFail);
                  return false;
                }
              }}
            >
              <ProFormText
                name="username"
                fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
                placeholder={text.username}
                rules={[{ required: true, message: text.usernameRequired }]}
              />
              <ProFormText.Password
                name="password"
                fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
                placeholder={text.password}
                rules={[{ required: true, message: text.passwordRequired }]}
              />
              {captcha.captchaEnabled && (
                <div className="captcha-row">
                  <ProFormText
                    name="code"
                    fieldProps={{ size: 'large', prefix: <SafetyOutlined /> }}
                    placeholder={text.code}
                    rules={[{ required: true, message: text.codeRequired }]}
                  />
                  <button type="button" className="captcha-button" onClick={loadCaptcha}>
                    <img className="captcha-image" src={`data:image/gif;base64,${captcha.img}`} alt="captcha" />
                  </button>
                </div>
              )}
              <div className="login-form-meta">
                <ProFormCheckbox name="rememberMe">{text.rememberMe}</ProFormCheckbox>
                <Button type="link" onClick={() => history.push('/register')}>
                  {text.register}
                </Button>
              </div>
            </LoginForm>
          </div>
        </section>
      </div>
    </ConfigProvider>
  );
}
