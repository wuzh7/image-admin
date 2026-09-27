# TRO 信息外部查询接口

## 接口

- 方法：`POST`
- 路径：`/prod-api/open-api/tro/cases/query`
- 请求头：`X-API-Key`
- 请求类型：`application/json`
- API Key 配置：生产环境变量 `TRO_API_KEY`

请求体：

```json
{
  "plaintiffName": "Omega SA",
  "plaintiff": "Omega SA",
  "caseNoFormat": ["26-cv-26647", "26-cv-26648"]
}
```

三个字段均为可选，但至少传一个。`plaintiffName` 和 `plaintiff` 的输入都会生成原文、去前后空格、去全部空白、转小写及组合后的最多六种查询值，不区分大小写地匹配 `tro_case.plaintiff` 或 `tro_case.plaintiff_names` 数组元素。`caseNoFormat` 可传字符串或字符串数组，逐项匹配案号。任一条件命中即返回该案件，同一案件只返回一次。

成功响应的 `data` 是数组：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "plaintiffNamesStr": "Omega SA，Omega中文",
      "plaintiff": "Omega SA",
      "caseNoFormat": "26-cv-26647"
    }
  ]
}
```

`plaintiffNamesStr` 是表中 `plaintiffNames` 数组按中文逗号 `，` 拼接的字符串。没有别名时返回空字符串；没有匹配记录时返回空数组。

缺失或错误的 `X-API-Key` 返回 HTTP 401；没有查询条件返回 HTTP 400。
