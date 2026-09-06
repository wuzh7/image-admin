import { AppstoreOutlined, PartitionOutlined, PictureOutlined, SettingOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons';
import { Icon } from '@iconify/react';
import { useBoolean } from 'ahooks';
import { Button, Input, Popover, Tooltip } from 'antd';
import { useMemo, useState } from 'react';

type IconComponent = typeof UserOutlined;

const iconOptions: Array<[string, IconComponent]> = [
  ['user', UserOutlined],
  ['peoples', TeamOutlined],
  ['system', SettingOutlined],
  ['tree-table', PartitionOutlined],
  ['picture', PictureOutlined]
];

const getIconTooltipContainer = (triggerNode: HTMLElement) => triggerNode.parentElement ?? document.body;

export interface IconSelectProps {
  value?: string;
  onChange?: (value: string) => void;
  width?: string | number;
}

export default function IconSelect({ value, onChange, width = '100%' }: IconSelectProps) {
  const [open, { set: setOpen, setFalse: closePopover }] = useBoolean(false);
  const [keyword, setKeyword] = useState('');
  const [customIcon, setCustomIcon] = useState('');

  const matchedIcons = useMemo(() => iconOptions.filter(([name]) => !keyword || name.includes(keyword)), [keyword]);

  const selectIcon = (name: string) => {
    onChange?.(name);
    closePopover();
  };

  const applyCustomIcon = () => {
    const next = customIcon.trim();
    if (!next) {
      return;
    }
    onChange?.(next);
    closePopover();
  };

  const CurrentIcon = iconOptions.find(([name]) => name === value)?.[1] || AppstoreOutlined;
  const prefixIcon = value?.includes(':') ? <Icon icon={value} /> : <CurrentIcon />;

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      trigger="click"
      placement="bottomLeft"
      content={
        <div className="icon-select-popover-react">
          <Input allowClear placeholder="搜索图标" value={keyword} onChange={event => setKeyword(event.target.value)} />
          <div className="icon-select-custom-react">
            <Input
              allowClear
              placeholder="也可以直接输入图标名"
              value={customIcon}
              onChange={event => setCustomIcon(event.target.value)}
              onPressEnter={applyCustomIcon}
            />
            <Button type="primary" onClick={applyCustomIcon}>
              使用
            </Button>
          </div>
          <div className="icon-select-list-react">
            {matchedIcons.map(([name, Icon]) => {
              return (
                <Tooltip
                  key={name}
                  title={name}
                  placement="bottom"
                  getPopupContainer={getIconTooltipContainer}
                  styles={{ root: { pointerEvents: 'none' } }}
                >
                  <button
                    type="button"
                    className={`icon-select-item-react${value === name ? ' active' : ''}`}
                    onClick={() => selectIcon(name)}
                  >
                    <Icon />
                  </button>
                </Tooltip>
              );
            })}
          </div>
        </div>
      }
    >
      <Input readOnly style={{ width }} value={value} placeholder="点击选择图标" prefix={prefixIcon} />
    </Popover>
  );
}
