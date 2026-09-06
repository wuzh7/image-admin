import { RotateLeftOutlined, RotateRightOutlined, UploadOutlined } from '@ant-design/icons';
import { PageContainer, ProDescriptions } from '@ant-design/pro-components';
import {
  Avatar,
  Button,
  Card,
  Col,
  Form,
  Input,
  message,
  Modal,
  Radio,
  Row,
  Slider,
  Space,
  Tabs,
  Upload,
  type UploadProps
} from 'antd';
import { useCallback, useEffect, useMemo, useState, type ComponentType } from 'react';
import CropperModule, { type Area, type CropperProps, type Point } from 'react-easy-crop';
import type { UserInfoVO, UserProfileForm } from '@/api/system/user/types';
import { uploadOss } from '@/api/system/oss';
import { getUserProfile, updateUserProfile, updateUserPwd } from '@/api/system/user';
import defaultAvatar from '@/assets/images/profile.jpg';
import { useDict } from '@/hooks/useDict';
import { useLoading } from '@/hooks/useLoading';
import { usePermissionStore } from '@/stores/permissionStore';
import { useUserStore } from '@/stores/userStore';
import { removeToken } from '@/utils/auth';
import { history } from '@umijs/max';
import { dictOptions } from '@/utils/dict';
import { getUploadErrorMessage, validateUploadFile } from '@/utils/upload';

// react-easy-crop 6.2.x 的 CommonJS 类型声明遗漏了默认导出，运行时仍为默认组件。
type CropperComponentProps = Partial<CropperProps> & Pick<CropperProps, 'crop' | 'onCropChange'>;
const Cropper = CropperModule as unknown as ComponentType<CropperComponentProps>;

const avatarFileTypes = ['png', 'jpg', 'jpeg'];
const avatarOutputSize = 200;
const initialAvatarCrop: Point = { x: 0, y: 0 };

function createImage(url: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new window.Image();
    image.addEventListener('load', () => resolve(image));
    image.addEventListener('error', error => reject(error));
    image.src = url;
  });
}

function getRadianAngle(degreeValue: number) {
  return (degreeValue * Math.PI) / 180;
}

function rotateSize(width: number, height: number, rotation: number) {
  const rotRad = getRadianAngle(rotation);
  return {
    width: Math.abs(Math.cos(rotRad) * width) + Math.abs(Math.sin(rotRad) * height),
    height: Math.abs(Math.sin(rotRad) * width) + Math.abs(Math.cos(rotRad) * height)
  };
}

async function getCroppedAvatarBlob(imageSrc: string, crop: Area, rotation: number) {
  const image = await createImage(imageSrc);
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  const rotRad = getRadianAngle(rotation);
  const { width: bBoxWidth, height: bBoxHeight } = rotateSize(image.naturalWidth, image.naturalHeight, rotation);

  canvas.width = bBoxWidth;
  canvas.height = bBoxHeight;
  ctx.translate(bBoxWidth / 2, bBoxHeight / 2);
  ctx.rotate(rotRad);
  ctx.translate(-image.naturalWidth / 2, -image.naturalHeight / 2);
  ctx.drawImage(image, 0, 0);

  const croppedCanvas = document.createElement('canvas');
  const croppedCtx = croppedCanvas.getContext('2d');
  if (!croppedCtx) return null;

  croppedCanvas.width = avatarOutputSize;
  croppedCanvas.height = avatarOutputSize;
  croppedCtx.drawImage(canvas, crop.x, crop.y, crop.width, crop.height, 0, 0, avatarOutputSize, avatarOutputSize);

  return new Promise<Blob | null>(resolve => croppedCanvas.toBlob(resolve, 'image/png'));
}

function avatarUploadFileName(fileName: string) {
  return fileName.replace(/\.[^.]+$/, '') || 'avatar';
}

export default function Profile() {
  const [userForm] = Form.useForm<UserProfileForm>();
  const [pwdForm] = Form.useForm<{ oldPassword: string; newPassword: string; confirmPassword: string }>();
  const reloadMenus = usePermissionStore(state => state.reloadMenus);
  const dicts = useDict('sys_user_gender');
  const [profile, setProfile] = useState<UserInfoVO>();
  const { loading, withLoading } = useLoading();
  const { loading: avatarUploading, withLoading: withAvatarUploading } = useLoading();
  const [avatarCropOpen, setAvatarCropOpen] = useState(false);
  const [avatarCropUrl, setAvatarCropUrl] = useState('');
  const [avatarFileName, setAvatarFileName] = useState('avatar.png');
  const [avatarCrop, setAvatarCrop] = useState<Point>(initialAvatarCrop);
  const [avatarZoom, setAvatarZoom] = useState(1);
  const [avatarRotation, setAvatarRotation] = useState(0);
  const [avatarCroppedAreaPixels, setAvatarCroppedAreaPixels] = useState<Area>();
  const user = profile?.user || {};
  const genderOptions = useMemo(() => dictOptions(dicts.sys_user_gender), [dicts.sys_user_gender]);

  const onAvatarCropComplete = useCallback((_croppedArea: Area, croppedAreaPixels: Area) => {
    setAvatarCroppedAreaPixels(croppedAreaPixels);
  }, []);

  const loadProfile = useCallback(async () => {
    await withLoading(async () => {
      const profileRes = await getUserProfile();
      setProfile(profileRes.data);
      userForm.setFieldsValue(profileRes.data.user || {});
    });
  }, [userForm, withLoading]);

  useEffect(() => {
    loadProfile();
  }, [loadProfile]);

  const submitUserInfo = async () => {
    const values = await userForm.validateFields();
    const profileForm: UserProfileForm = {
      nickName: values.nickName,
      phoneNumber: values.phoneNumber,
      email: values.email,
      gender: values.gender
    };
    await updateUserProfile(profileForm);
    message.success('修改成功');
    await reloadMenus();
    await loadProfile();
  };

  const submitPwd = async () => {
    const values = await pwdForm.validateFields();
    await updateUserPwd(values.oldPassword, values.newPassword);
    Modal.success({
      title: '密码已修改',
      content: '为保障账号安全，请使用新密码重新登录。',
      okText: '重新登录',
      onOk: () => {
        removeToken();
        useUserStore.getState().clearUserInfo();
        usePermissionStore.getState().clearMenus();
        history.replace('/login');
      }
    });
  };

  const uploadProps: UploadProps = {
    showUploadList: false,
    accept: avatarFileTypes.map(item => `.${item}`).join(','),
    beforeUpload: file => {
      if (
        !validateUploadFile(file, { fileTypes: avatarFileTypes, maxSizeMB: 5, fileKind: '头像图片', matchMime: true })
      ) {
        return Upload.LIST_IGNORE;
      }
      if (avatarCropUrl) {
        window.URL.revokeObjectURL(avatarCropUrl);
      }
      setAvatarFileName(file.name || 'avatar.png');
      setAvatarCrop(initialAvatarCrop);
      setAvatarZoom(1);
      setAvatarRotation(0);
      setAvatarCroppedAreaPixels(undefined);
      setAvatarCropUrl(window.URL.createObjectURL(file));
      setAvatarCropOpen(true);
      return Upload.LIST_IGNORE;
    }
  };

  const closeAvatarCrop = () => {
    if (avatarCropUrl) {
      window.URL.revokeObjectURL(avatarCropUrl);
    }
    setAvatarCropOpen(false);
    setAvatarCropUrl('');
    setAvatarCrop(initialAvatarCrop);
    setAvatarZoom(1);
    setAvatarRotation(0);
    setAvatarCroppedAreaPixels(undefined);
  };

  const submitAvatarCrop = async () => {
    if (!avatarCropUrl || !avatarCroppedAreaPixels) {
      message.warning('请先选择头像图片');
      return;
    }
    const blob = await getCroppedAvatarBlob(avatarCropUrl, avatarCroppedAreaPixels, avatarRotation);
    if (!blob) {
      message.error('头像裁剪失败');
      return;
    }
    const formData = new FormData();
    formData.append('file', blob, `${avatarUploadFileName(avatarFileName)}.png`);
    await withAvatarUploading(async () => {
      try {
        const res = await uploadOss(formData);
        if (!res.data.ossId) {
          throw new Error('头像上传失败');
        }
        await updateUserProfile({ avatar: res.data.ossId });
        message.success('修改成功');
        closeAvatarCrop();
        await reloadMenus();
        await loadProfile();
      } catch (error) {
        message.error(getUploadErrorMessage(error, '头像上传失败'));
      }
    });
  };

  return (
    <PageContainer title="个人中心" loading={loading}>
      <Row gutter={16}>
        <Col xs={24} lg={7}>
          <Card title="个人信息">
            <Space orientation="vertical" size={18} style={{ width: '100%' }}>
              <div style={{ textAlign: 'center' }}>
                <Avatar size={112} src={user.avatarUrl || defaultAvatar} />
                <div style={{ marginTop: 12 }}>
                  <Upload {...uploadProps}>
                    <Button icon={<UploadOutlined />} loading={avatarUploading}>
                      上传头像
                    </Button>
                  </Upload>
                </div>
              </div>
              <ProDescriptions
                column={1}
                dataSource={{
                  ...user,
                  roleGroup: profile?.roleGroup || '-'
                }}
                columns={[
                  { title: '用户名称', dataIndex: 'userName' },
                  { title: '手机号码', dataIndex: 'phoneNumber' },
                  { title: '用户邮箱', dataIndex: 'email' },
                  { title: '所属角色', dataIndex: 'roleGroup' },
                  { title: '创建日期', dataIndex: 'createTime' }
                ]}
              />
            </Space>
          </Card>
        </Col>
        <Col xs={24} lg={17}>
          <Card title="个人设置">
            <Tabs
              items={[
                {
                  key: 'userinfo',
                  label: '基本资料',
                  children: (
                    <Form form={userForm} layout="vertical" style={{ maxWidth: 560 }}>
                      <Form.Item
                        name="nickName"
                        label="用户昵称"
                        rules={[{ required: true, message: '用户昵称不能为空' }]}
                      >
                        <Input maxLength={30} />
                      </Form.Item>
                      <Form.Item
                        name="phoneNumber"
                        label="手机号码"
                        rules={[
                          { required: true, message: '手机号码不能为空' },
                          { pattern: /^1[3456789][0-9]\d{8}$/, message: '请输入正确的手机号码' }
                        ]}
                      >
                        <Input maxLength={11} />
                      </Form.Item>
                      <Form.Item
                        name="email"
                        label="邮箱"
                        rules={[{ required: true, type: 'email', message: '请输入正确的邮箱地址' }]}
                      >
                        <Input maxLength={50} />
                      </Form.Item>
                      <Form.Item name="gender" label="性别">
                        <Radio.Group options={genderOptions} />
                      </Form.Item>
                      <Form.Item>
                        <Button type="primary" onClick={submitUserInfo}>
                          保存
                        </Button>
                      </Form.Item>
                    </Form>
                  )
                },
                {
                  key: 'resetPwd',
                  label: '修改密码',
                  children: (
                    <Form form={pwdForm} layout="vertical" style={{ maxWidth: 560 }}>
                      <Form.Item
                        name="oldPassword"
                        label="旧密码"
                        rules={[{ required: true, message: '旧密码不能为空' }]}
                      >
                        <Input.Password placeholder="请输入旧密码" />
                      </Form.Item>
                      <Form.Item
                        name="newPassword"
                        label="新密码"
                        rules={[
                          { required: true, message: '新密码不能为空' },
                          { min: 6, max: 20, message: '长度在 6 到 20 个字符' },
                          { pattern: /^[^<>"'|\\]+$/, message: '不能包含非法字符：< > " \' \\ |' }
                        ]}
                      >
                        <Input.Password placeholder="请输入新密码" />
                      </Form.Item>
                      <Form.Item
                        name="confirmPassword"
                        label="确认密码"
                        dependencies={['newPassword']}
                        rules={[
                          { required: true, message: '确认密码不能为空' },
                          ({ getFieldValue }) => ({
                            validator(_, value) {
                              if (!value || getFieldValue('newPassword') === value) return Promise.resolve();
                              return Promise.reject(new Error('两次输入的密码不一致'));
                            }
                          })
                        ]}
                      >
                        <Input.Password placeholder="请确认新密码" />
                      </Form.Item>
                      <Form.Item>
                        <Button type="primary" onClick={submitPwd}>
                          保存
                        </Button>
                      </Form.Item>
                    </Form>
                  )
                }
              ]}
            />
          </Card>
        </Col>
      </Row>
      <Modal
        title="裁剪头像"
        open={avatarCropOpen}
        width={640}
        confirmLoading={avatarUploading}
        onOk={submitAvatarCrop}
        onCancel={closeAvatarCrop}
        destroyOnHidden
      >
        {avatarCropUrl && (
          <div className="avatar-cropper-react">
            <div style={{ position: 'relative', height: 360, width: '100%', background: '#111', borderRadius: 6 }}>
              <Cropper
                image={avatarCropUrl}
                crop={avatarCrop}
                zoom={avatarZoom}
                rotation={avatarRotation}
                aspect={1}
                cropShape="round"
                showGrid={false}
                onCropChange={setAvatarCrop}
                onZoomChange={setAvatarZoom}
                onRotationChange={setAvatarRotation}
                onCropComplete={onAvatarCropComplete}
              />
            </div>
            <Space orientation="vertical" size={12} style={{ marginTop: 12, width: '100%' }}>
              <Space>
                <Button icon={<RotateLeftOutlined />} onClick={() => setAvatarRotation(value => value - 90)}>
                  左旋转
                </Button>
                <Button icon={<RotateRightOutlined />} onClick={() => setAvatarRotation(value => value + 90)}>
                  右旋转
                </Button>
              </Space>
              <Slider min={1} max={3} step={0.1} value={avatarZoom} onChange={setAvatarZoom} />
            </Space>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
