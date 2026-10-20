# 随包中文字体（答卷 PDF 用）

本目录用于放置**答卷 PDF 导出**使用的中文字体，由 `ExamResultServiceImpl.loadCjkFont` 加载。

## 当前内置

| 文件 | 字体 | 来源 | 许可 |
|---|---|---|---|
| `NotoSansSC-Regular.otf` | Noto Sans SC Regular（SubsetOTF/SC） | https://github.com/notofonts/noto-cjk （`Sans/SubsetOTF/SC/`） | SIL OFL-1.1，见同目录 `LICENSE-OFL.txt` |

字体由 PDFBox **以子集方式内嵌**进答卷 PDF，阅读方不需要装字体。

## 加载顺序（代码内置，无需改代码）

1. 配置项 `exam.pdf.font-path` 指定的绝对路径（部署时想换字体就设它）；
2. **本目录**（classpath `fonts/`）：`NotoSansSC-*.otf|ttf`、`SourceHanSansCN-*.otf|ttf`、`simhei.ttf`、`msyh.ttc`、`wqy-microhei.ttc`；
3. 操作系统已装的中文字体（Windows `C:/Windows/Fonts/*`、Linux `noto/wqy/arphic`、macOS `PingFang.ttc`）。

三者都拿不到时 PDF 接口**不会失败**，而是降级成 ASCII 版（中文以 `?` 呈现）。

## 许可提示

内置 Noto Sans SC 为 SIL OFL-1.1，可随应用分发（保留 `LICENSE-OFL.txt` 即可）。
若替换为其它字体，请确认其再分发许可；**Windows 自带的 simhei/msyh 等微软字体不可随应用分发**——代码只把它们当运行期兜底，不进仓库。