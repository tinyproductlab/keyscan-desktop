# KeyScan安全备份兼容实现

桌面端严格保留Android现有格式：V5和V6 magic均为8字节并包含末尾NUL；盐16字节、IV 12字节、GCM标签128位；PBKDF2-HMAC-SHA256固定240,000次、输出256位，不使用AAD。

V6先用`rootKey`派生包装密钥并加密数据库密钥的Base64文本，再用该数据库密钥文本派生正文密钥。包装密文长度使用4字节大端整数，读取范围限制为16至1024。

当前Android的V6外层容器内部仍写业务JSON版本5。桌面端不得擅自把内部`version`改成6；完整业务JSON导入导出在独立任务中实现并使用Android黄金文件交叉验证。

业务JSON编解码器固定输出`version: 5`，顶层键为`records`、`passwordGroups`、`passwords`、`otpTokens`、`passwordGenerations`、`vaultItems`和`vaultAttachments`。读取允许Android通过`JSONObject.put(key, null)`省略的可空字段，并忽略未来新增键；数组数量和JSON总大小有上限。

V6正文是ZIP流而不是裸JSON。第一项必须是`payload.json`；附件项为`attachments/<id>.bin`，JSON中的`contentReference`必须与ZIP项名一致。读取器拒绝目录穿越、反斜杠、绝对路径、重复payload、未知附件项、超量条目以及超过上限的正文或附件。

“替换恢复”先在同一KeyScan数据目录的临时区完成整包GCM认证、JSON解析、所有附件SHA-256验证和使用当前数据库密钥的重新加密。通过后才移动附件并原子替换本地保险箱；提交前保留旧附件回滚副本，失败时恢复旧文件和旧快照。没有`contentReference`的旧附件元数据按Android行为跳过，不创建空附件。
