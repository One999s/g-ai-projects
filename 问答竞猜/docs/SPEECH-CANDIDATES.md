# 语音只生成候选

GET /api/quiz/me/capabilities在原身份与配额验证后返回voiceCandidateConfigured等配置状态。configured不等于模型健康、录音许可或生产验收。ASR默认关闭，无处理器时请求503 PRIVATE_ASR_NOT_CONFIGURED，不能回退到浏览器云识别或伪造文字。

POST /api/quiz/sessions/{id}/rounds/{round}/voice-candidate接受有明确Content-Length的audio/wav原始字节。仅支持0.1–6秒、mono16kHz PCM16及规范44字节头，最大192044字节。不是multipart，也不接受上传文件路径、URL、任意压缩媒体或客户端声称的用户/site。

顺序：原身份→Redis准入→Origin→当前归属/轮次/答题窗口→有限读取与WAV验证→再次身份检查→识别前窗口检查→私有处理器→识别后当前窗口/归属/身份复验→返回候选。过期后已有的权威超时状态保留，但迟到转写不会变成答案。

Redis在全部业务请求120/60秒之外，按site/user/session/round原子限制3次语音尝试；重试同样消费额度。窗口长度60秒长于20秒答题窗口，不能在同一题内等计数重置。未知或损坏计数仍拒绝，不放宽runtime ACL。

候选DTO只含sessionId、roundId、choice（不明确为null）、transcript、expiresAt、serverNow与requiresConfirmation=true。没有correct、加分或答题副作用。保守匹配完整选项/清楚的A–D或限定短句；多义、多个答案、已被50:50排除、额外话语默认不猜。完整选项文字与字母标签发生歧义也返回null。

浏览器必须再次展示候选，并由用户明确点击确认后调用普通answer API；不得把识别成功当作同意答题。录音请求前要说明处理位置、最长时长、保留策略并由用户主动触发麦克风权限。页面隐藏、题目变化、身份过期、卸载或截止应终止录音/清空候选；点击/键盘始终是替代路径。B13已接入server前端的主动录音/取消/候选核对与确认；仍需真实浏览器、麦克风和原身份环境验收。

Java请求体上限和读后时间检查仍不能替代反向代理绝对慢正文/连接边界。私有处理器的有限队列/线程/超时也不是未经测试的公网防御。部署见speech-worker/README.md；没有真实用户录音、中文识别或端到端原系统登录通过的声明。
