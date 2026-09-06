# SSCD 模型目录

本目录保存 Java DJL 运行图片去重所必需的 SSCD TorchScript 模型。约 94 MB 的模型二进制不提交到 Git，本地文件名固定为：

`models/sscd_disc_mixup.torchscript.pt`

生产环境通过 `SSCD_MODEL_PATH` 指向只读挂载的同名文件。模型 SHA-256 必须与项目交付清单一致。

Java 测试资源已内置 3 张基准图片，用于校验迁移后的相似度分数。24 张困难负样本不属于模型文件，也不在已删除的 Python Demo 中；如需运行对应回归测试，通过 `-Dsscd.hard-negative-dir=<目录>` 指向单独保存的样本目录。
