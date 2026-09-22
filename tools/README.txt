本目录是「验收脚本入口」，只放一张指路牌 —— 真正可运行的脚本副本在源码树里，
避免同一份脚本在这里和源码树里各存一份、日久走样：

    ../source/cdp/tools/rebuild_and_verify.sh   停/起模拟器 + 构建 + 装包 + 跑五组验收（推荐入口）
    ../source/cdp/tools/verify_all.sh           只跑五组（已装包时），输出同时写入 ../evidence/
    ../source/cdp/tools/verify_click.sh         第一组：点击链路（真实触摸、坐标精度、浮层诊断）
    ../source/cdp/tools/verify_record.sh        第二组：录制 → 回放（指纹 / 位置锚点 / 不许乱点）
    ../source/cdp/tools/verify_scripts.sh       第三组：用户脚本 / 导入 / 搜索 / 控制口绑定
    ../source/cdp/tools/verify_cdp.sh           第四组：外部 CDP 驱动 / 资源 / 稳定性
    ../source/cdp/tools/verify_waitcond.sh      第五组：等视频播完再点下一个 / 计数 / 历史书签 / ✕ 与☰
    ../source/cdp/tools/verify_ui.sh            第六组：界面按键可用性 + 下载功能（真实手指点）
    ../source/cdp/tools/verify_build_repro.sh   源码快照能否干净复现交付构件（逐 zip 条目比对）
    ../source/cdp/tools/_lib.sh                 公共部分（断言、等待、页面读数、设备在线检查）
    ../source/cdp/tools/cdp.mjs                 零依赖 CDP 探针（list/eval/nav/tap/mouse/key）

怎么跑：先看 ../env/环境配方.txt 里的 PATH 与设备端准备，然后

    cd ../source/cdp && bash tools/rebuild_and_verify.sh

本次交付跑过的结果见 ../evidence/01…06（第五组 + 构件复现一致性）。
注意：无头模拟器长时间连跑会自己崩（容器 Exit 139）；脚本会在设备离线时明确报「环境问题」而不是
把后面几十条断言伪装成应用失败 —— 重跑一次即可。
