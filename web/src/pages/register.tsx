import { LockOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { MailOutlined, MobileOutlined } from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, ConfigProvider, message, Modal } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useCallback, useEffect, useState } from 'react';
import type { RegisterParams, VerifyCodeResult } from '@/api/types';
import { getCodeImg, register } from '@/api/login';
import { useLoading } from '@/hooks/useLoading';
import { appEnv } from '@/utils/env';

const registerText = {
  brandTitle: '图片管理后台',
  brandDesc: '用于商品图片上传、相似度检测、人工审核和正式图库管理。',
  highlights: ['批量上传', '相似度检测', '人工审核', '图库管理'],
  formSubTitle: '创建新的业务工作台账号',
  username: '账号',
  password: '密码',
  confirmPassword: '确认密码',
  phoneNumber: '手机号码',
  email: '邮箱',
  code: '验证码',
  submit: '注册',
  submitting: '注册中...',
  login: '使用已有账号登录',
  tip: '注册后将返回登录页继续认证',
  successTitle: '系统提示',
  success: (username?: string) => `恭喜你，账号 ${username} 注册成功！`,
  fail: '注册失败',
  usernameRequired: '请输入账号',
  usernameLength: '账号长度必须介于 2 和 20 之间',
  passwordRequired: '请输入密码',
  passwordLength: '密码长度必须介于 5 和 20 之间',
  passwordPattern: '不能包含非法字符：< > " \' \\ |',
  confirmRequired: '请确认密码',
  confirmMismatch: '两次输入的密码不一致',
  phoneRequired: '请输入手机号码',
  phoneInvalid: '请输入正确的手机号码',
  emailRequired: '请输入邮箱',
  emailInvalid: '请输入正确的邮箱地址',
  codeRequired: '请输入验证码'
};

export default function Register() {
  const [captcha, setCaptcha] = useState<VerifyCodeResult>({ captchaEnabled: true });
  const { loading, withLoading } = useLoading();
  const text = registerText;

  const loadCaptcha = useCallback(async () => {
    const res = await getCodeImg();
    setCaptcha({
      ...res.data,
      captchaEnabled: res.data.captchaEnabled === undefined ? true : res.data.captchaEnabled
    });
  }, []);

  useEffect(() => {
    loadCaptcha().catch(() => setCaptcha({ captchaEnabled: false }));
  }, [loadCaptcha]);

  useEffect(() => {
    document.title = appEnv.title;
  }, []);

  const submitRegister = async (values: RegisterParams) => {
    await withLoading(async () => {
      try {
        await register({
          ...values,
          uuid: captcha.uuid,
          userType: 'sys_user'
        });
        await Modal.success({
          title: text.successTitle,
          content: text.success(values.username)
        });
        history.push('/login');
      } catch (error) {
        if (captcha.captchaEnabled) {
          loadCaptcha();
        }
        message.error((error as Error).message || text.fail);
      }
    });
  };

  return (
    <ConfigProvider locale={zhCN}>
      <div className="register-page">
        <section className="register-brand-react">
          <span className="register-brand-pill">图片管理后台</span>
          <h1>{text.brandTitle}</h1>
          <p>{text.brandDesc}</p>
          <div className="register-highlights">
            {text.highlights.map(item => (
              <span key={item}>{item}</span>
            ))}
          </div>
        </section>

        <section className="register-panel">
          <LoginForm<RegisterParams>
            title={appEnv.logoTitle}
            subTitle={text.formSubTitle}
            submitter={{
              searchConfig: { submitText: loading ? text.submitting : text.submit },
              submitButtonProps: { loading, block: true, size: 'large' }
            }}
            onFinish={async values => {
              await submitRegister(values);
              return true;
            }}
          >
            <ProFormText
              name="username"
              fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
              placeholder={text.username}
              rules={[
                { required: true, message: text.usernameRequired },
                { min: 2, max: 20, message: text.usernameLength }
              ]}
            />
            <ProFormText.Password
              name="password"
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              placeholder={text.password}
              rules={[
                { required: true, message: text.passwordRequired },
                { min: 5, max: 20, message: text.passwordLength },
                { pattern: /^[^<>"'|\\]+$/, message: text.passwordPattern }
              ]}
            />
            <ProFormText.Password
              name="confirmPassword"
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              placeholder={text.confirmPassword}
              dependencies={['password']}
              rules={[
                { required: true, message: text.confirmRequired },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) return Promise.resolve();
                    return Promise.reject(new Error(text.confirmMismatch));
                  }
                })
              ]}
            />
            <ProFormText
              name="phoneNumber"
              fieldProps={{ size: 'large', prefix: <MobileOutlined />, maxLength: 11 }}
              placeholder={text.phoneNumber}
              rules={[
                { required: true, message: text.phoneRequired },
                { pattern: /^1[3456789][0-9]\d{8}$/, message: text.phoneInvalid }
              ]}
            />
            <ProFormText
              name="email"
              fieldProps={{ size: 'large', prefix: <MailOutlined />, maxLength: 50 }}
              placeholder={text.email}
              rules={[
                { required: true, message: text.emailRequired },
                { type: 'email', message: text.emailInvalid }
              ]}
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
            <div className="register-actions">
              <span>{text.tip}</span>
              <Button type="link" onClick={() => history.push('/login')}>
                {text.login}
              </Button>
            </div>
          </LoginForm>
        </section>
      </div>
    </ConfigProvider>
  );
}
