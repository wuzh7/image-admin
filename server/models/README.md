# SSCD 模型目录

本目录保存 Java DJL 运行图片去重所必需的 SSCD TorchScript 模型。约 94 MB 的模型二进制不提交到 Git，本地文件名固定为：

`models/sscd_disc_mixup.torchscript.pt`

克隆仓库后，从 [Facebook Research 的官方 SSCD 模型](https://github.com/facebookresearch/sscd-copy-detection#models) 下载到此目录：

```bash
curl -fL https://dl.fbaipublicfiles.com/sscd-copy-detection/sscd_disc_mixup.torchscript.pt -o server/models/sscd_disc_mixup.torchscript.pt
shasum -a 256 server/models/sscd_disc_mixup.torchscript.pt
```

Windows PowerShell 在仓库根目录执行：

```powershell
Invoke-WebRequest -Uri https://dl.fbaipublicfiles.com/sscd-copy-detection/sscd_disc_mixup.torchscript.pt -OutFile server/models/sscd_disc_mixup.torchscript.pt
Get-FileHash server/models/sscd_disc_mixup.torchscript.pt -Algorithm SHA256
```

预期 SHA-256：`9f26bd4c848cc19b73d2ae92eea6e04886f61a7b764ceb7a13aeee62e6a6db56`。下载失败或哈希不符时不要启动服务。生产环境通过 `SSCD_MODEL_HOST_PATH` 挂载同一模型；文件仍保存在服务器本地，不进入 Git。

Java 测试资源已内置 3 张基准图片，用于校验迁移后的相似度分数。24 张困难负样本不属于模型文件，也不在已删除的 Python Demo 中；如需运行对应回归测试，通过 `-Dsscd.hard-negative-dir=<目录>` 指向单独保存的样本目录。
