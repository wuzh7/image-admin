# 部门树与用户、部门、角色关系对照（2026-09-29）

## 一手参考

- RuoYi-Vue-Plus 6.X 的[部门实体](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/SysDept.java)保留 `parentId`、`ancestors`、`orderNum`；[部门接口](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/SysDeptController.java)的 `/list` 返回 `List<SysDeptVo>`，[服务](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/service/impl/SysDeptServiceImpl.java)直接从 mapper 返回平铺列表，另为选择框构建树。其[官方配套 Vue 前端](https://github.com/CrazyLionCat/plus-ui/blob/045fc006725c014455def9fed19f93c781633d26/src/views/system/dept/index.vue)在 `getList` 中用 `handleTree(res.data, 'deptId')` 组出 `children`，并以 `row-key="deptId"`、`tree-props.children` 渲染树表；有展开/折叠按钮。
- 上游的[用户实体](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/SysUser.java)只有一个 `deptId`；[用户角色关联](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/SysUserRole.java)为独立关联表，允许一个用户拥有多个角色；[角色实体](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/SysRole.java)有 `dataScope`，[角色部门关联](https://github.com/dromara/RuoYi-Vue-Plus/blob/9227f626a3a37e1546d6c291ed398183e94df92b/ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/domain/SysRoleDept.java)用于角色自定义数据范围。
- [Ant Design Table 文档](https://ant.design/components/table/)明确：数据行有 `children` 才显示树结构；`childrenColumnName` 可改字段名；`rowExpandable` 控制哪些行可展开，`defaultExpandAllRows` 仅指定初始展开状态。文档没有替应用定义“筛选命中子部门时如何展示祖先”的业务规则。

## 与本项目对照

| 关系或界面 | 本项目现状 | 结论 |
| --- | --- | --- |
| 部门层级 | [`sys_dept.parent_id`、`ancestors`、`order_num`](../../server/script/sql/wuzh_vue.sql) 已有；[`/list`](../../server/wuzh-modules/wuzh-system/src/main/java/org/dromara/system/controller/system/SysDeptController.java)返回平铺行，`/treeselect` 已构树 | 数据模型足够，不需要改表。2026-09-29 线上只读核对：`测试1.parent_id=总测试.dept_id`，层级数据正确。 |
| 列表展示 | [`ProTable` 页面](../../web/src/pages/system/dept/index.tsx)直接把平铺 `/list` 数据传入表格，只有 `defaultExpandAllRows` | 缺少把 `parentId` 映射为 `children` 的一步，是当前层级不可见的直接原因。 |
| 用户归属、授权 | [`sys_user.dept_id`、`sys_user_role`](../../server/script/sql/wuzh_vue.sql)分别表示单部门归属和用户多角色；[`sys_dept_role`](../../server/wuzh-modules/wuzh-system/src/main/java/org/dromara/system/service/SysDeptRoleService.java)限定部门可用角色，分配用户角色时服务端校验 | 保持这三层，不宜把“部门角色”理解为部门内用户自动获得该角色。 |
| 数据与菜单权限 | [`sys_role_dept`、`sys_dept_menu`](../../server/script/sql/wuzh_vue.sql)分别表示角色的部门数据范围、部门菜单/按钮上限 | `sys_role_dept` 与 `sys_dept_role` 方向相反，不能合表或互相替代；树展示改动不应改变权限语义。 |
| 上级部门选择 | 原页面用自由输入和同名匹配；未匹配时服务端会自动在固定默认根部门下创建上级部门 | 用户确认业务上允许多个顶级公司。新增时应明确区分选择已有部门、新建顶级公司并创建子部门、当前部门作为顶级公司；编辑顶级公司应显示“无上级”。 |

当前有效菜单/按钮权限为**用户角色授予的权限与所属部门权限上限的交集**。`sys_role_dept` 是角色可访问的业务数据部门范围，并不是“部门拥有的角色”。部门之间的父子关系用于组织结构和部分数据范围，不代表子部门自动继承上级的角色或菜单权限。超级管理员是全局身份，不放进部门角色候选项。

## 建议改动及验收

1. **列表层级**：复用 `/list` 的 `deptId`、`parentId`，在页面数据层组树；现有 `handleTree` 会给叶子保留空 `children`，因此这里采用只给真正父部门设置 `children` 的组树方式。提供“展开全部 / 收起全部”。验收：`总测试 → 测试1` 缩进显示，收起总测试时测试1隐藏，新增/移动/删除后层级刷新正确。依据是[上游前端实现](https://github.com/CrazyLionCat/plus-ui/blob/045fc006725c014455def9fed19f93c781633d26/src/views/system/dept/index.vue)和[Ant Design 树表契约](https://ant.design/components/table/)。
2. **搜索路径**：在完整且已授权的部门集合上筛选，命中子部门时保留并展开祖先路径；前端展示层处理，不改查询 API。验收：搜索“测试1”仍看到 `总测试 → 测试1`。这属于本项目交互决策，[Ant Design 文档](https://ant.design/components/table/)没有规定。
3. **多根与上级部门完整性**：新增提供三种明确方式：选择已有部门、新建顶级公司并创建当前子部门、当前部门直接作为顶级公司。新建上级公司写 `parent_id=0, ancestors=0`；编辑顶级公司显示“无上级”，允许把普通部门移为顶级公司。服务端拒绝不存在、停用或本部门下级作为上级，并在同一事务中创建新公司和子部门。验收：新公司与原公司并列，删除/移动后祖级链正确。
4. **保留授权关系**：用户单部门、多角色、部门角色候选、角色数据范围、部门菜单上限各司其职；没有多部门归属的业务需求，不增加关联表或整套移植另一框架。继续检查用户新增/编辑/分配角色、角色分配权限、部门分配权限这三条服务端校验链。

核对依据：项目源码、线上只读部门记录及上述公开一手资料。线上业务数据未修改。
