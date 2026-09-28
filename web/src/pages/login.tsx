import { AppstoreOutlined, BarChartOutlined, GlobalOutlined, LockOutlined, PictureOutlined, SafetyOutlined, ShopOutlined, UserOutlined, VideoCameraOutlined } from '@ant-design/icons';
import { LoginForm, ProFormCheckbox, ProFormText } from '@ant-design/pro-components';
import { history, useSearchParams } from '@umijs/max';
import { ConfigProvider, message } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useCallback, useEffect, useState } from 'react';
import type { VerifyCodeResult } from '@/api/types';
import { getCodeImg, getInfo, login } from '@/api/login';
import { isRelogin, userErrorMessage } from '@/api/request';
import { useTagsViewStore } from '@/stores/tagsViewStore';
import { getToken, removeToken, setToken } from '@/utils/auth';
import { appEnv } from '@/utils/env';
import haiyitongLogo from '@/assets/images/haiyitong-logo.png';

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
    username: rememberMe ? username : undefined,
    password: undefined,
    rememberMe
  };
}

const authText = {
  formSubTitle: 'AI 驱动，让跨境电商更简单',
  username: '用户账号',
  password: '密码',
  code: '验证码',
  rememberMe: '记住我',
  loginSuccess: '登录成功',
  loginFail: '登录失败',
  usernameRequired: '请输入用户账号',
  passwordRequired: '请输入密码',
  codeRequired: '请输入验证码'
};

export default function Login() {
  const [params] = useSearchParams();
  const resetTags = useTagsViewStore(state => state.resetTags);
  const [captcha, setCaptcha] = useState<VerifyCodeResult>({ captchaEnabled: true });
  const [initialValues] = useState(getRememberedLogin);
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
        <section className="login-showcase">
          <div className="login-showcase-grid" aria-hidden="true" />
          <div className="login-showcase-glow login-showcase-glow-one" aria-hidden="true" />
          <div className="login-showcase-glow login-showcase-glow-two" aria-hidden="true" />
          <svg className="login-showcase-lines" viewBox="0 0 900 760" aria-hidden="true">
            <defs>
              <linearGradient id="login-line-blue" x1="0" y1="0" x2="1" y2="1">
                <stop stopColor="#2dd4ff" stopOpacity=".1" />
                <stop offset=".5" stopColor="#63a7ff" stopOpacity=".9" />
                <stop offset="1" stopColor="#8b5cf6" stopOpacity=".05" />
              </linearGradient>
            </defs>
            <path d="M-80 620C160 350 260 690 430 430S690 170 980 250" stroke="url(#login-line-blue)" strokeWidth="2" fill="none" />
            <path d="M20 750C180 520 340 520 460 620s220-40 460-300" stroke="url(#login-line-blue)" strokeWidth="1.5" fill="none" opacity=".65" />
            <circle cx="430" cy="430" r="8" fill="#5ee7ff" /><circle cx="430" cy="430" r="25" stroke="#5ee7ff" opacity=".25" fill="none" />
            <circle cx="735" cy="270" r="5" fill="#8b5cf6" /><circle cx="225" cy="610" r="5" fill="#5ee7ff" />
          </svg>
          <header className="login-showcase-header">
            <div className="login-logo-wrap">
              <img src={haiyitongLogo} alt="海益通 HAI YI TONG" />
            </div>
            <span>GLOBAL E-COMMERCE<br />SOLUTIONS</span>
          </header>
          <div className="login-showcase-copy">
            <p className="login-showcase-kicker">CROSS-BORDER E-COMMERCE</p>
            <h1>让全球生意<br /><em>更简单</em></h1>
            <p className="login-showcase-desc">从图库管理到商品增长，海益通提供一站式智能运营服务。</p>
            <div className="login-showcase-services">
              <span><PictureOutlined />图库管理</span>
              <span><BarChartOutlined />图片去重</span>
              <span><SafetyOutlined />侵权检测</span>
              <span><ShopOutlined />店铺数据</span>
              <span><AppstoreOutlined />商品渲染</span>
              <span><VideoCameraOutlined />图片 · 视频生成</span>
            </div>
          </div>
          <div className="login-showcase-orb" aria-hidden="true">
          </div>
          <footer className="login-showcase-footer">
            <span>图库管理</span><i /> <span>店铺增长</span>
          </footer>
        </section>
        <section className="login-panel">
          <div className="login-panel-bg" aria-hidden="true"><span /><span /><span /></div>
          <img className="login-mobile-logo" src={haiyitongLogo} alt="海益通 HAI YI TONG" />
          <div className="login-panel-inner">
            <div className="login-panel-kicker">HAI YI TONG · AI BUSINESS PLATFORM</div>
            <LoginForm
              title="欢迎登录海益通"
              subTitle={authText.formSubTitle}
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
                  message.success(authText.loginSuccess);
                  history.replace(normalizeLoginRedirect(redirect));
                  return true;
                } catch (error) {
                  if (captcha.captchaEnabled) {
                    loadCaptcha();
                  }
                  message.error(userErrorMessage(error, authText.loginFail));
                  return false;
                }
              }}
            >
              <ProFormText
                name="username"
                fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
                placeholder={authText.username}
                rules={[{ required: true, message: authText.usernameRequired }]}
              />
              <ProFormText.Password
                name="password"
                fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
                placeholder={authText.password}
                rules={[{ required: true, message: authText.passwordRequired }]}
              />
              {captcha.captchaEnabled && (
                <div className="captcha-row">
                  <ProFormText
                    name="code"
                    fieldProps={{ size: 'large', prefix: <SafetyOutlined /> }}
                    placeholder={authText.code}
                    rules={[{ required: true, message: authText.codeRequired }]}
                  />
                  <button type="button" className="captcha-button" onClick={loadCaptcha}>
                    <img className="captcha-image" src={`data:image/gif;base64,${captcha.img}`} alt="captcha" />
                  </button>
                </div>
              )}
              <div className="login-form-meta">
                <ProFormCheckbox name="rememberMe">{authText.rememberMe}</ProFormCheckbox>
              </div>
            </LoginForm>
            <div className="login-panel-security">
              <span><GlobalOutlined />全球服务</span>
              <span><SafetyOutlined />数据安全</span>
              <span><AppstoreOutlined />高效运营</span>
            </div>
          </div>
          <p className="login-panel-caption">让每一张图片，都更接近成交</p>
        </section>
      </div>
    </ConfigProvider>
  );
}
