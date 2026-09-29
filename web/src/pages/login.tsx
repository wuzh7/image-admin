import { AppstoreOutlined, BarChartOutlined, CheckCircleFilled, GlobalOutlined, LockOutlined, PictureOutlined, PlayCircleFilled, RocketOutlined, SafetyCertificateOutlined, SafetyOutlined, ShopOutlined, ThunderboltFilled, UserOutlined, VideoCameraOutlined } from '@ant-design/icons';
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
import loginAiCube from '@/assets/images/login-ai-cube.png';

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
          <div className="login-showcase-horizon" aria-hidden="true" />
          <header className="login-showcase-header">
            <div className="login-logo-wrap">
              <img src={haiyitongLogo} alt="海益通 HAI YI TONG" />
            </div>
            <div className="login-showcase-tagline">
              <strong>让全球生意 · 更简单</strong>
              <span>MAKE GLOBAL BUSINESS EASIER</span>
            </div>
          </header>
          <div className="login-showcase-copy">
            <h1><span>AI</span> 驱动<br />助力跨境电商高效增长</h1>
            <p className="login-showcase-lead">设计走向商品，经营回到数据</p>
            <p className="login-showcase-desc">从素材到上架，一站式解决方案</p>
            <div className="login-showcase-rule" />
            <div className="login-showcase-services">
              <span className="service-image"><PictureOutlined />图片生成</span>
              <span className="service-safety"><SafetyCertificateOutlined />侵权检测</span>
              <span className="service-render"><AppstoreOutlined />商品渲染</span>
              <span className="service-video"><VideoCameraOutlined />视频生成</span>
            </div>
            <p className="login-showcase-motto">更智能 · 更高效 · 更专业</p>
          </div>
          <div className="login-hero-visual" aria-hidden="true">
            <img src={loginAiCube} alt="" />
            <div className="login-hero-note note-image"><PictureOutlined /><strong>图片生成</strong><small>创意变现</small></div>
            <div className="login-hero-note note-video"><PlayCircleFilled /><strong>视频生成</strong><small>让产品更生动</small></div>
            <div className="login-hero-note note-safety"><SafetyCertificateOutlined /><strong>侵权检测</strong><small>降低风险</small></div>
            <div className="login-hero-note note-data"><BarChartOutlined /><strong>店铺数据</strong><small>数据驱动增长</small></div>
          </div>
          <div className="login-showcase-benefits">
            <div><i className="benefit-blue"><ThunderboltFilled /></i><span><strong>高效管理</strong><small>海量素材，智能整理</small></span></div>
            <div><i className="benefit-green"><CheckCircleFilled /></i><span><strong>安全合规</strong><small>侵权检测，降低风险</small></span></div>
            <div><i className="benefit-purple"><AppstoreOutlined /></i><span><strong>创意无限</strong><small>图片 / 视频生成，提升转化</small></span></div>
          </div>
          <div className="login-showcase-one-stop">
            <strong>一站式服务</strong>
            <span>图片管理 · AI生成 · 数据驱动</span>
          </div>
          <footer className="login-showcase-footer">
            <span>HAI YI TONG</span>
            <span>GLOBAL E-COMMERCE SOLUTIONS</span>
          </footer>
        </section>
        <section className="login-panel">
          <div className="login-panel-bg" aria-hidden="true"><span /><span /><span /></div>
          <div className="login-panel-topline">GLOBAL<br />E-COMMERCE<br />SOLUTIONS</div>
          <img className="login-mobile-logo" src={haiyitongLogo} alt="海益通 HAI YI TONG" />
          <div className="login-panel-art" aria-hidden="true">
            <div className="login-art-screen">
              <div className="login-art-chart"><i /><i /><i /><i /><i /></div>
            </div>
            <div className="login-art-keyboard" />
            <span className="login-art-icon art-picture"><PictureOutlined /></span>
            <span className="login-art-icon art-shop"><ShopOutlined /></span>
            <span className="login-art-icon art-global"><GlobalOutlined /></span>
          </div>
          <div className="login-panel-inner">
            <LoginForm
              title="欢迎登录海益通"
              subTitle={authText.formSubTitle}
              initialValues={initialValues}
              submitter={{ searchConfig: { submitText: '登 录  →' }, submitButtonProps: { size: 'large', block: true } }}
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
            <div className="login-panel-divider"><span>安全 · 高效 · 全球化</span></div>
            <div className="login-panel-security">
              <span><SafetyCertificateOutlined />数据安全</span>
              <span><RocketOutlined />高效稳定</span>
              <span><GlobalOutlined />全球服务</span>
            </div>
          </div>
          <p className="login-panel-caption"><strong>用 AI 创造更多可能</strong><span>MORE POSSIBILITIES WITH AI</span></p>
        </section>
      </div>
    </ConfigProvider>
  );
}
